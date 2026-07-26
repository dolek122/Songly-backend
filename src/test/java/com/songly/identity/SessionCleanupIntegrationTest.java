package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Sprzątanie stoi na natywnym SQL-u i predykacie łączącym dwie kolumny, więc
 * sprawdzamy je na prawdziwym Postgresie — mock repozytorium potwierdziłby
 * jedynie, że job woła metodę, a nie że kasuje właściwe wiersze.
 *
 * <p>
 * Harmonogram wyłączony: job wołamy ręcznie, żeby test nie zależał od tego,
 * kiedy akurat wypadnie przebieg.
 */
@TestPropertySource(properties = {"songly.security.session-cleanup.enabled=false",
		"songly.now-playing.poll.enabled=false", "songly.now-playing.stream.heartbeat.enabled=false"})
class SessionCleanupIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private SessionRepository sessionRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID userId;

	@BeforeEach
	void createUser() {
		userId = UUID.randomUUID();
		userRepository.save(new User(userId, "session-cleanup-%s@songly.io".formatted(userId), "hash", Instant.now()));
	}

	/**
	 * Wstawiamy przez SQL, bo encja nie pozwala (słusznie) ustawić dowolnej daty
	 * odwołania — a właśnie wiek martwych wierszy jest tu przedmiotem testu.
	 */
	private UUID insertSession(Instant expiresAt, Instant revokedAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO sessions (id, user_id, refresh_token_hash, created_at, expires_at, revoked, revoked_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", id, userId, "hash-" + id, at(Instant.now().minus(60, ChronoUnit.DAYS)), at(expiresAt),
				revokedAt != null, at(revokedAt));
		return id;
	}

	/** Sterownik Postgresa nie potrafi zbindować {@link Instant} wprost. */
	private static OffsetDateTime at(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private boolean exists(UUID sessionId) {
		return sessionRepository.findById(sessionId).isPresent();
	}

	@Test
	void removesExpiredAndLongRevokedSessions_keepingLiveAndRecentlyDeadOnes() {
		Instant now = Instant.now();
		Instant cutoff = now.minus(7, ChronoUnit.DAYS);

		UUID longExpired = insertSession(now.minus(30, ChronoUnit.DAYS), null);
		UUID longRevoked = insertSession(now.plus(23, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS));
		UUID justExpired = insertSession(now.minus(1, ChronoUnit.DAYS), null);
		UUID justRevoked = insertSession(now.plus(29, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		UUID live = insertSession(now.plus(30, ChronoUnit.DAYS), null);

		int deleted = sessionRepository.deleteDeadSessions(cutoff, 5_000);

		assertThat(deleted).isEqualTo(2);
		assertThat(exists(longExpired)).isFalse();
		// Sedno kolumny revoked_at: ta sesja ma expires_at 23 dni w przyszłość, więc
		// po samym expires_at przeżyłaby jeszcze trzy tygodnie mimo że jest martwa.
		assertThat(exists(longRevoked)).isFalse();
		assertThat(exists(justExpired)).isTrue();
		assertThat(exists(justRevoked)).isTrue();
		assertThat(exists(live)).isTrue();
	}

	@Test
	void respectsBatchSize() {
		Instant old = Instant.now().minus(30, ChronoUnit.DAYS);
		for (int i = 0; i < 5; i++) {
			insertSession(old, null);
		}

		assertThat(sessionRepository.deleteDeadSessions(Instant.now().minus(7, ChronoUnit.DAYS), 2)).isEqualTo(2);
		assertThat(sessionRepository.deleteDeadSessions(Instant.now().minus(7, ChronoUnit.DAYS), 2)).isEqualTo(2);
		assertThat(sessionRepository.deleteDeadSessions(Instant.now().minus(7, ChronoUnit.DAYS), 2)).isEqualTo(1);
	}

	@Test
	void neverTouchesSessionsThatCanStillAuthenticate() {
		UUID live = insertSession(Instant.now().plus(30, ChronoUnit.DAYS), null);

		// Cutoff w przyszłości — nawet wtedy żywa sesja musi przetrwać, bo warunek
		// patrzy na wygaśnięcie i odwołanie, a nie na wiek wiersza.
		sessionRepository.deleteDeadSessions(Instant.now().plus(1, ChronoUnit.DAYS), 5_000);

		assertThat(exists(live)).isTrue();
	}
}
