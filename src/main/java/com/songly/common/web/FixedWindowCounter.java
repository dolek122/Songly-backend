package com.songly.common.web;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class FixedWindowCounter {

	private static final Logger log = LoggerFactory.getLogger(FixedWindowCounter.class);

	private final StringRedisTemplate redisTemplate;

	FixedWindowCounter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	Long increment(String key, Duration window) {
		try {
			Long count = redisTemplate.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redisTemplate.expire(key, window);
			}
			return count;
		} catch (RuntimeException ex) {
			log.warn("Rate limiting wyłączone dla tego żądania — Redis nie odpowiada: {}", ex.toString());
			return null;
		}
	}

	long retryAfterSeconds(String key, Duration window) {
		try {
			Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
			if (ttl != null && ttl > 0) {
				return ttl;
			}
			if (ttl != null && ttl == -1L) {
				log.warn("Licznik limitu bez TTL — przywracam okno {}", window);
				redisTemplate.expire(key, window);
			}
		} catch (RuntimeException ex) {
			log.warn("Nie udało się odczytać TTL okna limitu: {}", ex.toString());
		}
		return Math.max(1, window.toSeconds());
	}
}
