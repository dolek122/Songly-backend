package com.songly.common.web;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Licznik fixed-window w Redisie, wspólny dla limitu per IP
 * ({@link RateLimitingFilter}) i per użytkownik
 * ({@link UserRateLimitingFilter}). Oba limitery różnią się wyłącznie tym, czym
 * kluczują i co liczą — mechanika okna i zachowanie przy awarii Redisa muszą
 * być identyczne, więc siedzą w jednym miejscu.
 */
@Component
class FixedWindowCounter {

	private static final Logger log = LoggerFactory.getLogger(FixedWindowCounter.class);

	private final StringRedisTemplate redisTemplate;

	FixedWindowCounter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * Gdy Redis nie odpowiada, zwraca {@code null} i wołający przepuszcza żądanie,
	 * zamiast zwracać błąd. Rate limiting jest zabezpieczeniem, nie warunkiem
	 * poprawności — awaria cache'a nie może odciąć wszystkim logowania ani zapisów.
	 */
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

	/**
	 * Ile realnie zostało do końca okna — klient nie musi zgadywać ani dobijać się
	 * co sekundę. Gdy TTL jest niedostępny, podajemy pełne okno: lepiej kazać
	 * czekać za długo niż za krótko.
	 *
	 * <p>
	 * Klucz <b>bez TTL</b> ({@code -1}) dostaje go tutaj. {@code INCR} i
	 * {@code EXPIRE} to dwa osobne polecenia, więc zerwane połączenie albo restart
	 * między nimi zostawiał licznik, który nigdy nie wygasa — i ten adres albo
	 * użytkownik był odcięty na zawsze. Naprawa przy odrzuceniu kosztuje jedno
	 * polecenie i tylko wtedy, gdy ktoś i tak dostaje 429.
	 */
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
