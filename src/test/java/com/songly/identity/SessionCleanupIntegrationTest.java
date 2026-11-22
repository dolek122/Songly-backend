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

	private UUID insertSession(Instant expiresAt, Instant revokedAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO sessions (id, user_id, refresh_token_hash, created_at, expires_at, revoked, revoked_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", id, userId, "hash-" + id, at(Instant.now().minus(60, ChronoUnit.DAYS)), at(expiresAt),
				revokedAt != null, at(revokedAt));
		return id;
	}

	private static OffsetDateTime at(Instant instant) {
		return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
	}

	private boolean exists(UUID sessionId) {
		return sessionRepository.findById(sessionId).isPresent();
	}

	@Test
	void removesExpiredSessions_keepingRevokedOnesUntilTheyExpire() {
		Instant now = Instant.now();
		Instant cutoff = now.minus(7, ChronoUnit.DAYS);

		UUID longExpired = insertSession(now.minus(30, ChronoUnit.DAYS), null);
		UUID longRevoked = insertSession(now.plus(23, ChronoUnit.DAYS), now.minus(30, ChronoUnit.DAYS));
		UUID justExpired = insertSession(now.minus(1, ChronoUnit.DAYS), null);
		UUID justRevoked = insertSession(now.plus(29, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS));
		UUID live = insertSession(now.plus(30, ChronoUnit.DAYS), null);

		int deleted = sessionRepository.deleteDeadSessions(cutoff, 5_000);

		assertThat(deleted).isEqualTo(1);
		assertThat(exists(longExpired)).isFalse();
		assertThat(exists(longRevoked)).isTrue();
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

		sessionRepository.deleteDeadSessions(Instant.now().plus(1, ChronoUnit.DAYS), 5_000);

		assertThat(exists(live)).isTrue();
	}
}
