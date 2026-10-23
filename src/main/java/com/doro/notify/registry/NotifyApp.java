package com.doro.notify.registry;

/** 알림을 요청·수신하는 앱(생산자 서비스). {@code scopePath} 는 알림 주소가 가리킬 수 있는 범위다(예: {@code /party/}). */
public record NotifyApp(String appId, String scopePath, String displayName) {
}
