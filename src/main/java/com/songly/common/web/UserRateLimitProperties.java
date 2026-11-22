package com.songly.common.web;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.security.user-rate-limit")
class UserRateLimitProperties {

	private static final List<String> WRITE_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

	private boolean enabled = true;

	private List<Rule> rules = List
			.of(new Rule("writes", WRITE_METHODS, List.of("/api/**"), 60, Duration.ofMinutes(1)),
					new Rule(
							"interactions", List.of("PUT", "POST"), List.of("/api/v1/posts/*/likes",
									"/api/v1/posts/*/shares", "/api/v1/posts/*/comments", "/api/v1/friends/requests"),
							40, Duration.ofMinutes(10)));

	boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	List<Rule> getRules() {
		return rules;
	}

	void setRules(List<Rule> rules) {
		this.rules = rules;
	}

	static class Rule {

		private String name;
		private List<String> methods = WRITE_METHODS;
		private List<String> paths = List.of();
		private int limit;
		private Duration window = Duration.ofMinutes(1);

		Rule() {
		}

		Rule(String name, List<String> methods, List<String> paths, int limit, Duration window) {
			this.name = name;
			this.methods = methods;
			this.paths = paths;
			this.limit = limit;
			this.window = window;
		}

		String getName() {
			return name;
		}

		void setName(String name) {
			this.name = name;
		}

		List<String> getMethods() {
			return methods;
		}

		void setMethods(List<String> methods) {
			this.methods = methods;
		}

		List<String> getPaths() {
			return paths;
		}

		void setPaths(List<String> paths) {
			this.paths = paths;
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
	}
}
