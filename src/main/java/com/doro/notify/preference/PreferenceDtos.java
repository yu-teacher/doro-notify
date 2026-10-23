package com.doro.notify.preference;

import java.util.List;

public final class PreferenceDtos {

    private PreferenceDtos() {
    }

    /** 설정 화면의 한 줄: 종류 등록부의 문구와 지금 켜져 있는지(설정 행이 없으면 기본값). */
    public record TypeView(String type, String label, String description, boolean defaultOn, boolean enabled) {
    }

    public record AppView(String app, String displayName, List<TypeView> types) {
    }

    public record PreferencesResponse(List<AppView> apps) {
    }

    public record Change(String app, String type, Boolean enabled) {
    }

    public record UpdateRequest(List<Change> changes) {
    }
}
