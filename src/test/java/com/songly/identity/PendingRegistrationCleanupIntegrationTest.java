package com.songly.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Wygasłe zgłoszenia rejestracji znikają z bazy (ADR 0009). Mail weryfikacyjny
 * obiecuje osobie, której adres ktoś wpisał, że zgłoszenie „samo wygaśnie" — do
 * tej pory wygasał tylko link, a adres i hash hasła zostawały na zawsze.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PendingRegistrationCleanupIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private PendingRegistrationCleanupJob job;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	/** Przez SQL, bo encja nie pozwala ustawić terminu w przeszłości. */
	private UUID insertPending(String email, Instant expiresAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO pending_registrations (id, email, password_hash, token_hash, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?)
				""", id, email, "hash", "token-" + id, at(expiresAt.minus(1, ChronoUnit.DAYS)), at(expiresAt));
		return id;
	}

	private static OffsetDateTime at(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private boolean exists(UUID id) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM pending_registrations WHERE id = ?", Long.class,
				id) > 0;
	}

	@Test
	void removesExpiredRegistrations_keepsOnesStillWaitingForTheLink() {
		UUID expired = insertPending("pending-cleanup-expired@songly.io", Instant.now().minus(1, ChronoUnit.HOURS));
		UUID waiting = insertPending("pending-cleanup-waiting@songly.io", Instant.now().plus(1, ChronoUnit.HOURS));

		job.removeExpiredRegistrations();

		assertThat(exists(expired)).isFalse();
		assertThat(exists(waiting)).isTrue();
	}
}
