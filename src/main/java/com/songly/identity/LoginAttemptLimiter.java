package com.songly.identity;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Limit prób logowania na adres e-mail (ADR 0051), obok limitu per IP z
 * {@code RateLimitingFilter}. Tamten nie chroni konta przed atakiem
 * rozproszonym: każdy adres źródłowy ma własne okno, więc sto adresów dawało
 * sto razy więcej prób na jedno hasło.
 *
 * <p>
 * <b>Klucz to skrót adresu, bez sprawdzania, czy konto istnieje.</b> Adres bez
 * konta dostaje identyczny limit, więc odmowa nie mówi niczego o rejestracji
 * (§13). Skrót, a nie adres, bo klucze Redisa widać w narzędziach do
 * monitorowania, a adres to dane osobowe.
 *
 * <p>
 * <b>Liczymy każdą próbę przed sprawdzeniem hasła, a udane logowanie zeruje
 * licznik.</b> Liczenie samych porażek wymagałoby odczytu przed bcryptem i
 * zapisu po nim, a równoległe próby przechodziłyby między jednym a drugim.
 * {@code INCR} jest atomowy, więc limit trzyma przy dowolnej równoległości, a
 * prośba ponad limit nie kosztuje nawet bcrypta.
 *
 * <p>
 * Świadomy koszt: obcy może zablokować logowanie na cudzy adres na długość
 * okna. Zalogowane urządzenia działają dalej (refresh tokenu nie jest
 * limitowany), a okno jest krótkie. Fail-open jak reszta limitów: awaria Redisa
 * nie odcina logowania.
 */
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

	/**
	 * @throws LoginRateLimitExceededException
	 *             gdy ta próba przekracza limit dla adresu
	 */
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

	/**
	 * Licznik bez TTL (INCR przeszedł, EXPIRE nie) dostaje okno tutaj, inaczej
	 * adres zostałby zablokowany na zawsze — ta sama pułapka co w
	 * {@code FixedWindowCounter}.
	 */
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
