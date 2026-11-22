package com.songly.common.web;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.rate-limit")
class RateLimitProperties {

	private boolean enabled = true;
	private int limit = 10;
	private Duration window = Duration.ofMinutes(1);

	private List<String> protectedPaths = List.of("/api/v1/auth/register", "/api/v1/auth/register/verify",
			"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/password/reset-request",
			"/api/v1/auth/password/reset", "/api/v1/now-playing/spotify/callback", "/account-deletion",
			"/account-deletion/confirm");

	boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	int getLimit() {
		return limit;
	}

	void setLimit(int limit) {
		this.limit = limit;
	}

	Duration getWindow() {
		return window;
	}

	void setWindow(Duration window) {
		this.window = window;
	}

	List<String> getProtectedPaths() {
		return protectedPaths;
	}

	void setProtectedPaths(List<String> protectedPaths) {
		this.protectedPaths = protectedPaths;
	}
}
