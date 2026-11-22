package com.songly.profile;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.profile.genre-popularity")
class GenrePopularityProperties {

	private Duration ttl = Duration.ofHours(1);

	Duration getTtl() {
		return ttl;
	}

	void setTtl(Duration ttl) {
		this.ttl = ttl;
	}
}
