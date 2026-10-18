package com.songly.common.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.push")
class PushProperties {

	private String provider = "log";

	/**
	 * Treść JSON klucza konta serwisowego Firebase (nie ścieżka — na Render łatwiej
	 * o zmienną).
	 */
	private String fcmCredentialsJson;

	private String fcmBaseUrl = "https://fcm.googleapis.com";

	String getProvider() {
		return provider;
	}

	void setProvider(String provider) {
		this.provider = provider;
	}

	String getFcmCredentialsJson() {
		return fcmCredentialsJson;
	}

	void setFcmCredentialsJson(String fcmCredentialsJson) {
		this.fcmCredentialsJson = fcmCredentialsJson;
	}

	String getFcmBaseUrl() {
		return fcmBaseUrl;
	}

	void setFcmBaseUrl(String fcmBaseUrl) {
		this.fcmBaseUrl = fcmBaseUrl;
	}
}
