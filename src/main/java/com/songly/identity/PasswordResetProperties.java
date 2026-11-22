package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.password-reset")
class PasswordResetProperties {

	private Duration ttl = Duration.ofMinutes(30);

	private Duration resendInterval = Duration.ofMinutes(1);

	private String resetUrl = "songly://reset-password?token={token}";

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

	String getResetUrl() {
		return resetUrl;
	}

	void setResetUrl(String resetUrl) {
		this.resetUrl = resetUrl;
	}
}
