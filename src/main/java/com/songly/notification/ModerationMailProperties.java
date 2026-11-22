package com.songly.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.notification.moderation-mail")
class ModerationMailProperties {

	private Duration moderatorInterval = Duration.ofMinutes(15);

	Duration getModeratorInterval() {
		return moderatorInterval;
	}

	void setModeratorInterval(Duration moderatorInterval) {
		this.moderatorInterval = moderatorInterval;
	}
}
