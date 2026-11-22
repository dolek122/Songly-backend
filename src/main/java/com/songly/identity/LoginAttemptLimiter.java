package com.songly.identity;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class LoginAttemptLimiter {

	private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

	private static final String KEY_PREFIX = "login-attempts:";

	private final StringRedisTemplate redisTemplate;
	private final int limit;
	private final Duration window;

	LoginAttemptLimiter(StringRedisTemplate redisTemplate, @Value("${songly.auth.login.account-limit:10}") int limit,
			@Value("${songly.auth.login.account-window:15m}") Duration window) {
		this.redisTemplate = redisTemplate;
		this.limit = limit;
		this.window = window;
	}

	void recordAttempt(String email) {
		String key = key(email);
		Long attempts;
		try {
			attempts = redisTemplate.opsForValue().increment(key);
			if (attempts != null && attempts == 1L) {
				redisTemplate.expire(key, window);
			}
		} catch (RuntimeException ex) {
			log.warn("Limit prób logowania wyłączony dla tej próby — Redis nie odpowiada: {}", ex.toString());
			return;
		}
		if (attempts != null && attempts > limit) {
			throw new LoginRateLimitExceededException(retryAfterSeconds(key));
		}
	}

	void reset(String email) {
		try {
			redisTemplate.delete(key(email));
		} catch (RuntimeException ex) {
			log.warn("Nie udało się wyzerować licznika prób logowania: {}", ex.toString());
		}
	}

	private long retryAfterSeconds(String key) {
		try {
			Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
			if (ttl != null && ttl > 0) {
				return ttl;
			}
			redisTemplate.expire(key, window);
		} catch (RuntimeException ex) {
			log.warn("Nie udało się odczytać TTL limitu prób logowania: {}", ex.toString());
		}
		return Math.max(1, window.toSeconds());
	}

	private static String key(String email) {
		return KEY_PREFIX + SecureTokens.hash(email);
	}
}
