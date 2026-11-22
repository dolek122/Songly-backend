package com.songly.identity;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.registration")
class RegistrationProperties {

	private Duration ttl = Duration.ofHours(24);

	private Duration resendInterval = Duration.ofMinutes(1);

	private String verificationUrl = "songly://verify-email?token={token}";

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

	String getVerificationUrl() {
		return verificationUrl;
	}

	void setVerificationUrl(String verificationUrl) {
		this.verificationUrl = verificationUrl;
	}
}
