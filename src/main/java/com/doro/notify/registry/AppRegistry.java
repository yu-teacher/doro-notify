package com.doro.notify.registry;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static com.doro.notify.common.db.Db.ts;

/** 앱 등록부(마이그레이션 시드)와 알림 종류 등록부. */
@Repository
public class AppRegistry {

    private final JdbcClient jdbc;
    private final Clock clock;

    public AppRegistry(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Optional<NotifyApp> findApp(String appId) {
        if (appId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT app_id, scope_path, display_name FROM notify_apps WHERE app_id = :id")
                .param("id", appId)
                .query((rs, n) -> new NotifyApp(rs.getString("app_id"), rs.getString("scope_path"), rs.getString("display_name")))
                .optional();
    }

    public List<NotifyApp> apps() {
        return jdbc.sql("SELECT app_id, scope_path, display_name FROM notify_apps ORDER BY app_id")
                .query((rs, n) -> new NotifyApp(rs.getString("app_id"), rs.getString("scope_path"), rs.getString("display_name")))
                .list();
    }

    public List<NotifyType> allTypes() {
        return jdbc.sql("SELECT app_id, type_id, label, description, default_on, deprecated FROM notify_types ORDER BY app_id, type_id")
                .query((rs, n) -> new NotifyType(rs.getString("app_id"), rs.getString("type_id"), rs.getString("label"),
                        rs.getString("description"), rs.getBoolean("default_on"), rs.getBoolean("deprecated")))
                .list();
    }

    public Optional<NotifyType> findType(String appId, String typeId) {
        return jdbc.sql("SELECT app_id, type_id, label, description, default_on, deprecated FROM notify_types WHERE app_id = :a AND type_id = :t")
                .param("a", appId).param("t", typeId)
                .query((rs, n) -> new NotifyType(rs.getString("app_id"), rs.getString("type_id"), rs.getString("label"),
                        rs.getString("description"), rs.getBoolean("default_on"), rs.getBoolean("deprecated")))
                .optional();
    }

    /** 앱이 마운트된 공개 경로(알림 주소가 가리킬 수 있는 범위)를 바꾼다. 서비스의 공개 주소가 옮겨질 때 서비스가 스스로 알린다. */
    @Transactional
    public void updateScopePath(String appId, String scopePath) {
        jdbc.sql("UPDATE notify_apps SET scope_path = :s WHERE app_id = :a").param("s", scopePath).param("a", appId).update();
    }

    /**
     * 앱의 알림 종류를 등록한다(멱등). 목록에 있는 종류는 upsert 하고 deprecated 를 해제하며, 목록에 없는 기존 종류는 지우지 않고
     * deprecated 로 표시한다(사용자 설정 행을 보존한다). 그러므로 호출자는 **현재 쓰는 종류 전체**를 보내야 한다.
     */
    @Transactional
    public void registerTypes(String appId, List<NotifyType> types) {
        for (NotifyType type : types) {
            jdbc.sql("""
                    INSERT INTO notify_types (app_id, type_id, label, description, default_on, deprecated, updated_at)
                    VALUES (:a, :t, :label, :description, :defaultOn, FALSE, :now)
                    ON CONFLICT (app_id, type_id) DO UPDATE SET label = EXCLUDED.label, description = EXCLUDED.description,
                        default_on = EXCLUDED.default_on, deprecated = FALSE, updated_at = EXCLUDED.updated_at""")
                    .param("a", appId).param("t", type.typeId()).param("label", type.label())
                    .param("description", type.description()).param("defaultOn", type.defaultOn())
                    .param("now", ts(clock.instant()))
                    .update();
        }
        List<String> keep = types.stream().map(NotifyType::typeId).toList();
        String notInKeep = keep.isEmpty() ? "" : "AND type_id NOT IN (:keep)";
        var update = jdbc.sql("UPDATE notify_types SET deprecated = TRUE, updated_at = :now WHERE app_id = :a AND deprecated = FALSE " + notInKeep)
                .param("a", appId).param("now", ts(clock.instant()));
        if (!keep.isEmpty()) {
            update = update.param("keep", keep);
        }
        update.update();
    }
}
