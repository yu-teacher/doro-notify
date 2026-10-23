package com.doro.notify.account;

import com.hunnit_beasts.doro.sdk.sync.SyncCursorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 탈퇴 동기화의 진행 위치를 DB({@code iam_sync_state})에 둔다. 기준 시각은 **앞으로만** 움직인다(이른 값은 무시). */
@Component
class JdbcSyncCursorStore implements SyncCursorStore {

    private final JdbcTemplate jdbc;

    JdbcSyncCursorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Instant> get(String name) {
        List<OffsetDateTime> found = jdbc.queryForList("SELECT cursor_at FROM iam_sync_state WHERE name = ?", OffsetDateTime.class, name);
        return found.stream().findFirst().map(OffsetDateTime::toInstant);
    }

    @Override
    public void advance(String name, Instant cursor) {
        // 한 문장으로: 없으면 넣고, 있으면 더 늦은 값일 때만 바꾼다(동시에 두 번 불려도 뒤로 가지 않는다)
        jdbc.update("""
                INSERT INTO iam_sync_state (name, cursor_at) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET cursor_at = EXCLUDED.cursor_at, updated_at = now()
                WHERE EXCLUDED.cursor_at > iam_sync_state.cursor_at""", name, OffsetDateTime.ofInstant(cursor, ZoneOffset.UTC));
    }
}
