package com.doro.notify.common.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** JDBC 드라이버가 받는 시각 형식(OffsetDateTime)과 도메인의 Instant 사이 변환. 시각은 항상 주입된 Clock 에서 얻어 SQL 에 넘긴다(DB now() 에 기대지 않는다). */
public final class Db {

    private Db() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }
}
