package com.songly.content;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.content.report")
class ReportProperties {

	private int limit = 20;

	private Duration window = Duration.ofHours(1);

	private int dismissalPenalty = 5;

	private int minLimit = 1;

	private Duration historyWindow = Duration.ofDays(90);

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

	int getDismissalPenalty() {
		return dismissalPenalty;
	}

	void setDismissalPenalty(int dismissalPenalty) {
		this.dismissalPenalty = dismissalPenalty;
	}

	int getMinLimit() {
		return minLimit;
	}

	void setMinLimit(int minLimit) {
		this.minLimit = minLimit;
	}

	Duration getHistoryWindow() {
		return historyWindow;
	}

	void setHistoryWindow(Duration historyWindow) {
		this.historyWindow = historyWindow;
	}
}
