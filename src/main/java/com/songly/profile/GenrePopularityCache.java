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

/**
 * Obraz popularności gatunków trzymany w Redisie (ADR 0032) — dane do ważenia
 * dopasowań rzadkością gatunku (ADR 0029).
 *
 * <p>
 * Cache'ujemy <b>całą tabelę popularności naraz</b>, a nie podzbiór gatunków z
 * konkretnego żądania. Dwa powody. Gatunki są zamkniętym słownikiem z migracji
 * (dziś piętnaście), więc całość jest mniejsza niż narzut na cokolwiek
 * mądrzejszego. I ważniejsze: klucze per podzbiór rozpadłyby się na wpisy o
 * różnym wieku, a wtedy wagi dwóch gatunków w jednym wyniku pochodziłyby z
 * różnych epok — czyli dokładnie ta niespójność, przed którą ADR 0029 broni
 * liczeniem popularności globalnie.
 *
 * <p>
 * <b>Fail-open w każdej operacji.</b> Awaria Redisa musi kończyć się
 * policzeniem liczb z bazy, nie błędem dopasowań — cache jest tu optymalizacją,
 * nie warunkiem poprawności (ta sama zasada co w {@code RateLimitingFilter}).
 */
@Component
@EnableConfigurationProperties(GenrePopularityProperties.class)
class GenrePopularityCache {

	private static final Logger log = LoggerFactory.getLogger(GenrePopularityCache.class);

	/**
	 * Jeden klucz na całą instalację: liczby są globalne, więc nie ma czego dzielić
	 * per użytkownika ani per społeczność.
	 */
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
			// Uszkodzony wpis traktujemy jak brak wpisu: policzymy z bazy i nadpiszemy.
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

	/**
	 * Unieważnia obraz. Wołane <b>po commicie</b> transakcji, która zmieniła
	 * {@code user_genres} — patrz {@link FavoriteGenresChangedListener}.
	 */
	void evict() {
		try {
			redisTemplate.delete(KEY);
		} catch (RuntimeException ex) {
			// Nieudane unieważnienie znaczy tylko, że obraz dożyje swojego TTL.
			log.warn("Nie udało się unieważnić popularności gatunków w Redisie: {}", ex.getMessage());
		}
	}

	/**
	 * Popularność wszystkich gatunków i mianownik do jej przeliczenia — jedna
	 * niepodzielna całość, bo {@code ln(1 + N/n)} z liczbami z dwóch różnych
	 * momentów nie znaczy nic konkretnego.
	 */
	record Snapshot(Map<UUID, Long> counts, long totalUsers) {
	}
}
