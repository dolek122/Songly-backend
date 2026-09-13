package com.songly.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

/**
 * Fail-open cache'a popularności gatunków (ADR 0032): awaria Redisa nie może
 * wywrócić dopasowań, bo cache jest optymalizacją, nie warunkiem poprawności.
 *
 * <p>
 * Test jest tu dlatego, że tego jednego zachowania nie sprawdzi żaden test
 * integracyjny — Redis w Testcontainers działa, a psucie go w trakcie testu
 * (zatrzymanie kontenera) rozwaliłoby resztę suity, bo kontenery są wspólne.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GenrePopularityCacheTest {

	@Mock
	private StringRedisTemplate redisTemplate;

	@Mock
	private ValueOperations<String, String> valueOperations;

	private GenrePopularityCache cache() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new GenrePopularityCache(redisTemplate, new ObjectMapper(), new GenrePopularityProperties());
	}

	@Test
	void read_returnsEmpty_whenRedisIsDown() {
		when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("padło"));

		assertThat(cache().read()).isEmpty();
	}

	/** Uszkodzony wpis czyta się jak brak wpisu — policzymy z bazy i nadpiszemy. */
	@Test
	void read_returnsEmpty_whenCachedValueIsGarbage() {
		when(valueOperations.get(anyString())).thenReturn("{to nie jest JSON");

		assertThat(cache().read()).isEmpty();
	}

	@Test
	void read_returnsSnapshot_whenPresent() {
		UUID genreId = UUID.randomUUID();
		GenrePopularityCache cache = cache();
		when(valueOperations.get(anyString()))
				.thenReturn("{\"counts\":{\"%s\":7},\"totalUsers\":40}".formatted(genreId));

		assertThat(cache.read()).contains(new GenrePopularityCache.Snapshot(Map.of(genreId, 7L), 40L));
	}

	@Test
	void write_doesNotPropagate_whenRedisIsDown() {
		GenrePopularityCache cache = cache();
		doThrow(new RedisConnectionFailureException("padło")).when(valueOperations).set(anyString(), anyString(),
				any(java.time.Duration.class));

		assertThatCode(() -> cache.write(new GenrePopularityCache.Snapshot(Map.of(), 0L))).doesNotThrowAnyException();
	}

	/** Nieudane unieważnienie znaczy tylko, że obraz dożyje swojego TTL. */
	@Test
	void evict_doesNotPropagate_whenRedisIsDown() {
		when(redisTemplate.delete(anyString())).thenThrow(new RedisConnectionFailureException("padło"));
		GenrePopularityCache cache = new GenrePopularityCache(redisTemplate, new ObjectMapper(),
				new GenrePopularityProperties());

		assertThatCode(cache::evict).doesNotThrowAnyException();
	}
}
