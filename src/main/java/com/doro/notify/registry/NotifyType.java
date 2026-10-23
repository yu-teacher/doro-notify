package com.doro.notify.registry;

/** 앱이 등록한 알림 종류. 허브 설정 화면의 문구 출처이고, 설정 행이 없을 때의 기본값({@code defaultOn})을 갖는다. */
public record NotifyType(String appId, String typeId, String label, String description, boolean defaultOn, boolean deprecated) {
}
