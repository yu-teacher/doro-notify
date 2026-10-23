package com.doro.notify.request;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.delivery.DeliveryJobRepository;
import com.doro.notify.delivery.DeliveryJobRepository.NewJob;
import com.doro.notify.delivery.PayloadV1;
import com.doro.notify.preference.PreferenceService;
import com.doro.notify.push.WebPushEncryptor;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyApp;
import com.doro.notify.registry.NotifyType;
import com.doro.notify.request.NotificationDtos.NotificationRequest;
import com.doro.notify.request.NotificationDtos.NotificationResult;
import com.doro.notify.subscription.Subscription;
import com.doro.notify.subscription.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.doro.notify.common.db.Db.ts;

/**
 * 서비스 간 알림 요청을 받는다. 판단은 세 가지뿐이다: (a) 수신자가 이 앱·종류를 껐는가, (b) 같은 요청이 이미 왔는가, (c) 보낼 기기가 있는가.
 * 수신자 목록과 권한은 요청하는 서비스가 이미 정했다고 믿는다(업무 규칙은 여기 없다).
 *
 * <p>요청 기록(멱등 키)·수신자별 판정·발송 작업을 **한 트랜잭션**으로 저장하고 큐 적재 기준으로 응답한다(실제 전송은 워커).
 * 같은 (호출자, 멱등 키) 재요청은 아무것도 만들지 않고 처음 응답의 수치와 함께 DUPLICATE 로 돌려준다.
 */
@Service
public class NotificationRequestService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRequestService.class);

    static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    static final Pattern TYPE_IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_-]{0,47}$");
    private static final Pattern NO_CONTROL = Pattern.compile("^[^\\p{Cntrl}]+$");
    private static final int KEY_MAX = 128;

    private final RequestProperties limits;
    private final AppRegistry registry;
    private final PreferenceService preferences;
    private final SubscriptionRepository subscriptions;
    private final DeliveryJobRepository jobs;
    private final JdbcClient jdbc;
    private final Clock clock;

    public NotificationRequestService(RequestProperties limits, AppRegistry registry, PreferenceService preferences,
                                      SubscriptionRepository subscriptions, DeliveryJobRepository jobs, JdbcClient jdbc, Clock clock) {
        this.limits = limits;
        this.registry = registry;
        this.preferences = preferences;
        this.subscriptions = subscriptions;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * @param caller 서비스 토큰으로 확인된 호출자 이름(확인하지 못한 도입 단계에서는 null)
     */
    @Transactional
    public NotificationResult accept(String caller, NotificationRequest request) {
        Validated v = validate(request);
        Instant now = clock.instant();
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        if (v.expiresAt != null && !v.expiresAt.isAfter(now)) {
            // 이미 지난 알림: 오류가 아니다(호출자가 재시도를 멈추게 200). 아무것도 저장하지 않는다
            return new NotificationResult("EXPIRED", 0, 0, 0, 0);
        }

        Judgement judgement = judge(v);
        if (dryRun) {
            return new NotificationResult("DRY_RUN", judgement.accepted, judgement.suppressed, judgement.noSubscription, judgement.devices);
        }

        String owner = caller != null ? caller : "unverified:" + v.app.appId();
        UUID requestId = UUID.randomUUID();
        String traceId = MDC.get("traceId");
        int inserted = jdbc.sql("""
                INSERT INTO notify_requests (id, caller, idempotency_key, app_id, type_id, accepted, suppressed, no_subscription, devices, trace_id, created_at)
                VALUES (:id, :caller, :key, :app, :type, :accepted, :suppressed, :none, :devices, :trace, :now)
                ON CONFLICT (caller, idempotency_key) DO NOTHING""")
                .param("id", requestId).param("caller", owner).param("key", request.idempotencyKey())
                .param("app", v.app.appId()).param("type", v.type.typeId())
                .param("accepted", judgement.accepted).param("suppressed", judgement.suppressed).param("none", judgement.noSubscription)
                .param("devices", judgement.devices).param("trace", traceId).param("now", ts(now))
                .update();
        if (inserted == 0) {
            return duplicate(owner, request.idempotencyKey());
        }

        List<Object[]> recipientRows = new ArrayList<>();
        judgement.outcomes.forEach((user, outcome) -> recipientRows.add(new Object[] {requestId, user, outcome}));
        for (Object[] row : recipientRows) {
            jdbc.sql("INSERT INTO notify_request_recipients (request_id, user_id, outcome) VALUES (:r, :u, :o)")
                    .param("r", row[0]).param("u", row[1]).param("o", row[2]).update();
        }

        if (!judgement.targets.isEmpty()) {
            Instant expires = now.plus(v.ttl);
            if (v.expiresAt != null && v.expiresAt.isBefore(expires)) {
                expires = v.expiresAt;
            }
            byte[] payload = v.payload;
            jobs.supersede(judgement.targets.stream().map(Subscription::id).toList(), v.tag, now);
            Instant jobExpires = expires;
            List<NewJob> newJobs = judgement.targets.stream()
                    .map(s -> new NewJob(s.id(), v.app.appId(), v.tag, v.tag != null ? v.tag : requestId.toString(), v.urgent, payload, jobExpires))
                    .toList();
            jobs.insertAll(requestId, newJobs, traceId, now);
        }
        log.info("Notification accepted: caller={}, app={}, type={}, recipients={}, accepted={}, suppressed={}, noSubscription={}, devices={}",
                owner, v.app.appId(), v.type.typeId(), v.recipients.size(), judgement.accepted, judgement.suppressed, judgement.noSubscription, judgement.devices);
        return new NotificationResult("ACCEPTED", judgement.accepted, judgement.suppressed, judgement.noSubscription, judgement.devices);
    }

    private NotificationResult duplicate(String owner, String key) {
        return jdbc.sql("SELECT accepted, suppressed, no_subscription, devices FROM notify_requests WHERE caller = :c AND idempotency_key = :k")
                .param("c", owner).param("k", key)
                .query((rs, n) -> new NotificationResult("DUPLICATE", rs.getInt("accepted"), rs.getInt("suppressed"), rs.getInt("no_subscription"), rs.getInt("devices")))
                .single();
    }

    private record Judgement(int accepted, int suppressed, int noSubscription, int devices, Map<UUID, String> outcomes, List<Subscription> targets) {
    }

    private Judgement judge(Validated v) {
        Set<UUID> disabled = preferences.disabledAmong(v.recipients, v.app.appId(), v.type);
        List<UUID> candidates = v.recipients.stream().filter(u -> !disabled.contains(u)).toList();
        Map<UUID, List<Subscription>> byUser = new LinkedHashMap<>();
        for (Subscription s : subscriptions.findByUsersAndApp(candidates, v.app.appId())) {
            byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);
        }
        Map<UUID, String> outcomes = new LinkedHashMap<>();
        List<Subscription> targets = new ArrayList<>();
        int accepted = 0;
        int suppressed = 0;
        int none = 0;
        for (UUID user : v.recipients) {
            if (disabled.contains(user)) {
                outcomes.put(user, "SUPPRESSED_PREF");
                suppressed++;
            } else if (!byUser.containsKey(user)) {
                outcomes.put(user, "NO_SUBSCRIPTION");
                none++;
            } else {
                outcomes.put(user, "ACCEPTED");
                targets.addAll(byUser.get(user));
                accepted++;
            }
        }
        return new Judgement(accepted, suppressed, none, targets.size(), outcomes, targets);
    }

    private static final class Validated {
        NotifyApp app;
        NotifyType type;
        List<UUID> recipients;
        String tag;
        boolean urgent;
        Duration ttl;
        Instant expiresAt;
        byte[] payload;
    }

    private Validated validate(NotificationRequest r) {
        if (r == null) {
            throw new NotifyException(ErrorCode.INVALID_INPUT);
        }
        Validated v = new Validated();
        requireText(r.idempotencyKey(), KEY_MAX, "idempotencyKey");
        if (r.app() == null || !IDENTIFIER.matcher(r.app()).matches()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "app 이 올바르지 않습니다.");
        }
        v.app = registry.findApp(r.app()).orElseThrow(() -> new NotifyException(ErrorCode.UNKNOWN_APP));
        if (r.type() == null || !TYPE_IDENTIFIER.matcher(r.type()).matches()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "type 이 올바르지 않습니다.");
        }
        v.type = registry.findType(v.app.appId(), r.type()).orElseThrow(() -> new NotifyException(ErrorCode.UNKNOWN_TYPE));

        if (r.recipients() == null || r.recipients().isEmpty() || r.recipients().stream().anyMatch(java.util.Objects::isNull)) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "수신자가 필요합니다.");
        }
        Set<UUID> unique = new LinkedHashSet<>(r.recipients());
        if (unique.size() > limits.maxRecipients()) {
            throw new NotifyException(ErrorCode.TOO_MANY_RECIPIENTS);
        }
        v.recipients = List.copyOf(unique);

        requireText(r.title(), limits.titleMax(), "title");
        if (r.body() == null || r.body().length() > limits.bodyMax() || containsControl(r.body())) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "body 가 올바르지 않습니다.");
        }
        if (r.tag() != null && !r.tag().isEmpty()) {
            requireText(r.tag(), limits.tagMax(), "tag");
            v.tag = r.tag();
        }
        String url = UrlScopePolicy.require(r.url(), v.app.scopePath(), limits.urlMax());
        String icon = r.icon() == null || r.icon().isEmpty() ? null : UrlScopePolicy.require(r.icon(), v.app.scopePath(), limits.urlMax());

        long ttlSeconds = r.ttlSeconds() == null ? limits.defaultTtl().toSeconds() : r.ttlSeconds();
        if (ttlSeconds < 1 || ttlSeconds > limits.maxTtl().toSeconds()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "ttlSeconds 가 범위를 벗어났습니다.");
        }
        v.ttl = Duration.ofSeconds(ttlSeconds);
        String urgency = r.urgency() == null ? "normal" : r.urgency();
        if (!urgency.equals("normal") && !urgency.equals("high")) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, "urgency 는 normal 또는 high 여야 합니다.");
        }
        v.urgent = urgency.equals("high");
        v.expiresAt = r.expiresAt();

        v.payload = PayloadV1.build(v.app.appId(), v.type.typeId(), r.title(), r.body(), v.tag, url, icon);
        if (v.payload.length > WebPushEncryptor.MAX_PLAINTEXT_BYTES) {
            throw new NotifyException(ErrorCode.PAYLOAD_LIMIT);
        }
        return v;
    }

    private static void requireText(String value, int max, String field) {
        if (value == null || value.isEmpty() || value.length() > max || !NO_CONTROL.matcher(value).matches()) {
            throw new NotifyException(ErrorCode.INVALID_INPUT, field + " 가 올바르지 않습니다.");
        }
    }

    private static boolean containsControl(String value) {
        // 본문에는 줄바꿈·탭은 허용하고 그 밖의 제어 문자는 거절한다
        return value.chars().anyMatch(c -> (c < 0x20 && c != '\n' && c != '\t' && c != '\r') || c == 0x7f);
    }
}
