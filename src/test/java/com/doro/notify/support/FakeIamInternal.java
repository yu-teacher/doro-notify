package com.doro.notify.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 가짜 Doro IAM 내부 API: {@code GET /internal/v1/deleted-users?since=&limit=}. 서비스 토큰 헤더가 맞아야 응답한다(틀리면 401).
 * SDK 동기화가 규격(봉투·필드 이름·증분 기준)대로 호출해 우리 핸들러까지 닿는지 시험하기 위한 것이다.
 * 진짜 IAM 과의 연동은 이 환경에서 시험할 수 없다(doro-interview 와 같은 방식).
 */
public final class FakeIamInternal implements AutoCloseable {

    public static final String TOKEN = "test-internal-token";

    public record Deleted(UUID userId, Instant deletedAt) {
    }

    private final HttpServer server;
    private final List<Deleted> deleted = new CopyOnWriteArrayList<>();
    private final List<String> requestedSince = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    public FakeIamInternal() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/internal/v1/deleted-users", exchange -> {
            String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
            String since = "1970-01-01T00:00:00Z";
            int limit = 200;
            for (String pair : query.split("&")) {
                if (pair.startsWith("since=")) {
                    since = java.net.URLDecoder.decode(pair.substring(6), StandardCharsets.UTF_8);
                } else if (pair.startsWith("limit=")) {
                    limit = Integer.parseInt(pair.substring(6));
                }
            }
            requestedSince.add(since);
            String token = exchange.getRequestHeaders().getFirst("X-Doro-Service-Token");
            byte[] body;
            int code;
            if (status != 200) {
                code = status;
                body = "{\"success\":false,\"code\":\"DOWN\"}".getBytes(StandardCharsets.UTF_8);
            } else if (!TOKEN.equals(token)) {
                code = 401;
                body = "{\"success\":false,\"code\":\"AUTH-401\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                Instant from = Instant.parse(since);
                List<Deleted> items = new ArrayList<>(deleted.stream().filter(d -> !d.deletedAt().isBefore(from)).toList());
                items.sort(java.util.Comparator.comparing(Deleted::deletedAt));
                List<Deleted> page = items.subList(0, Math.min(limit, items.size()));
                Instant next = page.isEmpty() ? Instant.now() : page.get(page.size() - 1).deletedAt();
                StringBuilder sb = new StringBuilder("{\"success\":true,\"data\":{\"items\":[");
                for (int i = 0; i < page.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"userId\":\"").append(page.get(i).userId()).append("\",\"deletedAt\":\"").append(page.get(i).deletedAt()).append("\"}");
                }
                sb.append("],\"nextSince\":\"").append(next).append("\"}}");
                code = 200;
                body = sb.toString().getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void delete(UUID userId, Instant at) {
        deleted.add(new Deleted(userId, at));
    }

    public void clear() {
        deleted.clear();
        requestedSince.clear();
        status = 200;
    }

    /** IAM 이 죽어 있는 상황: 모든 요청이 이 상태 코드로 끝난다. */
    public void failWith(int httpStatus) {
        status = httpStatus;
    }

    public List<String> requestedSince() {
        return Collections.unmodifiableList(requestedSince);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
