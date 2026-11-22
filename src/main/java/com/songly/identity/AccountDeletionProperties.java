package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.account-deletion")
class AccountDeletionProperties {

	private Duration ttl = Duration.ofHours(1);

	private Duration resendInterval = Duration.ofMinutes(1);

	private String confirmUrl = "http://localhost:8080/account-deletion/confirm?token={token}";

	Duration getTtl() {
		return ttl;
	}

	void setTtl(Duration ttl) {
		this.ttl = ttl;
	}

	Duration getResendInterval() {
		return resendInterval;
	}

	void setResendInterval(Duration resendInterval) {
		this.resendInterval = resendInterval;
	}

	String getConfirmUrl() {
		return confirmUrl;
	}

	void setConfirmUrl(String confirmUrl) {
		this.confirmUrl = confirmUrl;
	}
}
