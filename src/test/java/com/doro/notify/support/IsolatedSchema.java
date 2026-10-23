package com.doro.notify.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 테스트 클래스마다 전용 Postgres 스키마를 만든다. 공유 DB 를 건드리지 않고 "빈 DB" 상태에서 시작하기 위해서다. 스택은 일회용이라(scripts/ci-test.sh 가 내린다) 만든 스키마는 따로 지우지 않는다.
 */
public final class IsolatedSchema {

    private IsolatedSchema() {
    }

    /** 새 스키마를 만들고, 그 스키마를 기본으로 쓰는 JDBC URL 을 돌려준다. */
    public static String createAndGetUrl() {
        String schema = "t_" + UUID.randomUUID().toString().replace("-", "");
        String base = baseUrl();
        try (Connection con = DriverManager.getConnection(base, user(), password()); Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("전용 스키마를 만들지 못했다: " + e.getMessage(), e);
        }
        return base + "?currentSchema=" + schema;
    }

    private static String baseUrl() {
        return "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "25434") + "/" + env("DB_NAME", "service_notify");
    }

    private static String user() {
        return env("DB_USER", "doro_admin");
    }

    private static String password() {
        return env("DB_PASSWORD", "ci-only-password");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }
}
