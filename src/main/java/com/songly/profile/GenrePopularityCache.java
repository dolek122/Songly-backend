package com.songly.profile;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@EnableConfigurationProperties(GenrePopularityProperties.class)
class GenrePopularityCache {

	private static final Logger log = LoggerFactory.getLogger(GenrePopularityCache.class);

	private static final String KEY = "genre-popularity";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;
	private final GenrePopularityProperties properties;

	GenrePopularityCache(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			GenrePopularityProperties properties) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	Optional<Snapshot> read() {
		try {
			String raw = redisTemplate.opsForValue().get(KEY);
			return raw == null ? Optional.empty() : Optional.of(objectMapper.readValue(raw, Snapshot.class));
		} catch (RuntimeException ex) {
			log.warn("Nie udało się odczytać popularności gatunków z Redisa, liczę z bazy: {}", ex.getMessage());
			return Optional.empty();
		}
	}

	void write(Snapshot snapshot) {
		try {
			redisTemplate.opsForValue().set(KEY, objectMapper.writeValueAsString(snapshot), properties.getTtl());
		} catch (RuntimeException ex) {
			log.warn("Nie udało się zapisać popularności gatunków w Redisie: {}", ex.getMessage());
		}
	}

	void evict() {
		try {
			redisTemplate.delete(KEY);
		} catch (RuntimeException ex) {
			log.warn("Nie udało się unieważnić popularności gatunków w Redisie: {}", ex.getMessage());
		}
	}

	record Snapshot(Map<UUID, Long> counts, long totalUsers) {
	}
}
