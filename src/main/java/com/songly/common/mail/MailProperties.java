package com.songly.common.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.mail")
class MailProperties {

	private String provider = "log";

	private String from = "no-reply@songly.io";

	String getProvider() {
		return provider;
	}

	void setProvider(String provider) {
		this.provider = provider;
	}

	String getFrom() {
		return from;
	}

	void setFrom(String from) {
		this.from = from;
	}
}
