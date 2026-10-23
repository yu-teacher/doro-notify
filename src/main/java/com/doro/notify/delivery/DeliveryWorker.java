package com.doro.notify.delivery;

import com.doro.notify.subscription.Subscription;
import com.doro.notify.subscription.SubscriptionRepository;
import com.doro.notify.push.PushProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 발송 큐를 비운다. 주기적으로 (1) 보낼 차례인 작업을 {@code FOR UPDATE SKIP LOCKED} 로 임대해 가져오고 (2) 푸시 서비스 호출 전용 스레드 풀에서 보내고
 * (3) 결과를 기록한다. 푸시 서비스가 느려도 요청 처리 스레드는 영향을 받지 않는다(벌크헤드).
 *
 * <p>재시도 정책: 일시 오류(시간 초과·429·5xx)는 지수 백오프로 다시 시도하되 총 {@code max-attempts} 번까지, 그리고 작업의 만료 시각(요청의 TTL·expiresAt 중
 * 빠른 쪽) 안에서만 한다. 다음 시도가 만료 뒤가 되면 버린다(DROPPED). 거절은 재시도하지 않는다(FAILED).
 * 어떤 경우에도 일시 오류만으로는 구독을 지우지 않는다. 끝난 작업의 페이로드는 비운다.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final String MDC_TRACE_ID = "traceId";
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final WorkerProperties worker;
    private final DeliveryJobRepository jobs;
    private final SubscriptionRepository subscriptions;
    private final PushDelivery delivery;
    private final Clock clock;
    private final ExecutorService executor;

    public DeliveryWorker(WorkerProperties worker, PushProperties push, DeliveryJobRepository jobs, SubscriptionRepository subscriptions,
                          PushDelivery delivery, Clock clock) {
        this.worker = worker;
        this.jobs = jobs;
        this.subscriptions = subscriptions;
        this.delivery = delivery;
        this.clock = clock;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "notify-push-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newFixedThreadPool(Math.max(1, push.workerThreads()), factory);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${notify.worker.poll-interval:PT1S}", initialDelayString = "${notify.worker.poll-interval:PT1S}")
    void poll() {
        if (!worker.enabled()) {
            return;
        }
        try {
            runUntilIdle();
        } catch (RuntimeException e) {
            // 다음 주기에 이어간다(DB 일시 장애 등). 작업 내용은 남기지 않는다
            log.warn("Delivery worker run failed: {}", e.getClass().getSimpleName(), e);
        }
    }

    /** 보낼 차례인 작업이 없을 때까지 가져와 처리한다. @return 이번에 처리한 작업 수 */
    public int runUntilIdle() {
        if (!delivery.enabled()) {
            return 0;
        }
        int total = 0;
        int claimed;
        do {
            List<DeliveryJob> batch = jobs.claimDue(clock.instant(), worker.batchSize(), worker.lease());
            claimed = batch.size();
            total += claimed;
            List<CompletableFuture<Void>> running = new ArrayList<>();
            for (DeliveryJob job : batch) {
                running.add(CompletableFuture.runAsync(() -> processSafely(job), executor));
            }
            running.forEach(CompletableFuture::join);
        } while (claimed >= worker.batchSize());
        return total;
    }

    private void processSafely(DeliveryJob job) {
        if (job.traceId() != null) {
            MDC.put(MDC_TRACE_ID, job.traceId());
        }
        try {
            process(job);
        } catch (RuntimeException e) {
            // 한 작업의 예기치 못한 오류가 배치의 다른 작업을 막지 않는다. 임대가 끝나면 다시 시도된다
            log.error("Delivery job failed unexpectedly: jobId={}", job.id(), e);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    void process(DeliveryJob job) {
        Instant now = clock.instant();
        if (!job.expiresAt().isAfter(now)) {
            jobs.markDropped(job.id(), null, now);
            log.info("Delivery dropped (expired): jobId={}, app={}", job.id(), job.appId());
            return;
        }
        Optional<Subscription> subscription = subscriptions.findById(job.subscriptionId());
        if (subscription.isEmpty() || job.payload() == null) {
            jobs.markDropped(job.id(), null, now);
            return;
        }
        Duration ttl = Duration.ofSeconds(Math.max(1, Duration.between(now, job.expiresAt()).toSeconds()));
        PushDelivery.Attempt attempt = delivery.attempt(subscription.get(), job.payload(), job.topic(), ttl, job.urgent());
        Instant after = clock.instant();
        int attemptsMade = job.attempts() + 1;
        switch (attempt.outcome()) {
            case DELIVERED -> {
                jobs.markSent(job.id(), attempt.status(), after);
                log.info("Delivery sent: jobId={}, app={}, attempts={}", job.id(), job.appId(), attemptsMade);
            }
            case GONE -> log.info("Delivery dropped (subscription gone): jobId={}, status={}", job.id(), attempt.status());
            case REJECTED -> {
                jobs.markFailed(job.id(), attempt.status(), after);
                log.warn("Delivery failed (rejected): jobId={}, app={}, status={}", job.id(), job.appId(), attempt.status());
            }
            case RETRYABLE -> retryOrGiveUp(job, attempt.status(), attemptsMade, after);
        }
    }

    private void retryOrGiveUp(DeliveryJob job, int status, int attemptsMade, Instant now) {
        if (attemptsMade >= worker.maxAttempts()) {
            jobs.markFailed(job.id(), status, now);
            log.warn("Delivery failed (retries exhausted): jobId={}, app={}, attempts={}, status={}", job.id(), job.appId(), attemptsMade, status);
            return;
        }
        Instant next = now.plus(worker.backoff().delay(attemptsMade, ThreadLocalRandom.current().nextDouble()));
        if (!next.isBefore(job.expiresAt())) {
            jobs.markDropped(job.id(), status, now);
            log.info("Delivery dropped (no retry before expiry): jobId={}, app={}, attempts={}, status={}", job.id(), job.appId(), attemptsMade, status);
            return;
        }
        jobs.markRetry(job.id(), status, next, now);
        log.info("Delivery will retry: jobId={}, app={}, attempts={}, status={}", job.id(), job.appId(), attemptsMade, status);
    }
}
