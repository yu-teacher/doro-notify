package com.doro.notify.preference;

import com.doro.notify.common.exception.ErrorCode;
import com.doro.notify.common.exception.NotifyException;
import com.doro.notify.preference.PreferenceDtos.AppView;
import com.doro.notify.preference.PreferenceDtos.Change;
import com.doro.notify.preference.PreferenceDtos.PreferencesResponse;
import com.doro.notify.preference.PreferenceDtos.TypeView;
import com.doro.notify.preference.PreferenceDtos.UpdateRequest;
import com.doro.notify.registry.AppRegistry;
import com.doro.notify.registry.NotifyType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.doro.notify.common.db.Db.ts;

/** 앱·종류별 알림 설정. 설정 행이 없으면 종류 등록부의 기본값을 따른다. */
@Service
public class PreferenceService {

    static final int MAX_CHANGES = 100;

    private final JdbcClient jdbc;
    private final AppRegistry registry;
    private final Clock clock;

    public PreferenceService(JdbcClient jdbc, AppRegistry registry, Clock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.clock = clock;
    }

    /** 종류 등록부와 합친 설정. deprecated 종류는 보여 주지 않는다(설정 행은 보존된다). */
    @Transactional(readOnly = true)
    public PreferencesResponse view(UUID userId) {
        Map<String, Boolean> saved = new HashMap<>();
        jdbc.sql("SELECT app_id, type_id, enabled FROM notify_preferences WHERE user_id = :u").param("u", userId)
                .query((rs, n) -> {
                    saved.put(rs.getString("app_id") + "\u0000" + rs.getString("type_id"), rs.getBoolean("enabled"));
                    return 0;
                }).list();
        Map<String, List<TypeView>> byApp = new LinkedHashMap<>();
        for (NotifyType type : registry.allTypes()) {
            if (type.deprecated()) {
                continue;
            }
            boolean enabled = saved.getOrDefault(type.appId() + "\u0000" + type.typeId(), type.defaultOn());
            byApp.computeIfAbsent(type.appId(), k -> new java.util.ArrayList<>())
                    .add(new TypeView(type.typeId(), type.label(), type.description(), type.defaultOn(), enabled));
        }
        List<AppView> apps = registry.apps().stream()
                .filter(app -> byApp.containsKey(app.appId()))
                .map(app -> new AppView(app.appId(), app.displayName(), byApp.get(app.appId())))
                .toList();
        return new PreferencesResponse(apps);
    }

    /** 변경을 한 트랜잭션으로 적용하고 합친 설정을 돌려준다. 등록되지 않은 앱·종류가 하나라도 있으면 전체를 거절한다. */
    @Transactional
    public PreferencesResponse update(UUID userId, UpdateRequest request) {
        List<Change> changes = request == null ? null : request.changes();
        if (changes == null || changes.isEmpty() || changes.size() > MAX_CHANGES) {
            throw new NotifyException(ErrorCode.INVALID_INPUT);
        }
        Set<String> known = new HashSet<>();
        registry.allTypes().forEach(t -> known.add(t.appId() + "\u0000" + t.typeId()));
        for (Change change : changes) {
            if (change == null || change.app() == null || change.type() == null || change.enabled() == null) {
                throw new NotifyException(ErrorCode.INVALID_INPUT);
            }
            if (!known.contains(change.app() + "\u0000" + change.type())) {
                throw new NotifyException(ErrorCode.UNKNOWN_TYPE);
            }
        }
        for (Change change : changes) {
            jdbc.sql("""
                    INSERT INTO notify_preferences (user_id, app_id, type_id, enabled, updated_at) VALUES (:u, :a, :t, :e, :now)
                    ON CONFLICT (user_id, app_id, type_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_at = EXCLUDED.updated_at""")
                    .param("u", userId).param("a", change.app()).param("t", change.type()).param("e", change.enabled())
                    .param("now", ts(clock.instant())).update();
        }
        return view(userId);
    }

    /** 수신자들 중 이 앱·종류를 **끈** 사람. (행이 있고 enabled=false 이거나, 행이 없는데 기본값이 꺼짐) */
    public Set<UUID> disabledAmong(Collection<UUID> userIds, String appId, NotifyType type) {
        Set<UUID> disabled = new HashSet<>();
        if (userIds.isEmpty()) {
            return disabled;
        }
        Map<UUID, Boolean> saved = new HashMap<>();
        jdbc.sql("SELECT user_id, enabled FROM notify_preferences WHERE app_id = :a AND type_id = :t AND user_id IN (:users)")
                .param("a", appId).param("t", type.typeId()).param("users", userIds)
                .query((rs, n) -> {
                    saved.put(rs.getObject("user_id", UUID.class), rs.getBoolean("enabled"));
                    return 0;
                }).list();
        for (UUID user : userIds) {
            if (!saved.getOrDefault(user, type.defaultOn())) {
                disabled.add(user);
            }
        }
        return disabled;
    }
}
