package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Rejestracja czekająca na potwierdzenie adresu e-mail. Konto powstaje dopiero
 * przy weryfikacji — do tego czasu żaden moduł poza {@code identity} nie wie o
 * istnieniu tego zgłoszenia.
 *
 * <p>
 * Hasło leży tu już zahaszowane bcryptem, tak samo jak w {@link User}. Odłożona
 * rejestracja nie może być miejscem, w którym hasło jest chronione słabiej niż
 * w koncie docelowym — użytkownik podał je raz i nie ma pojęcia, że przez
 * chwilę mieszka gdzie indziej.
 */
@Entity
@Table(name = "pending_registrations")
class PendingRegistration {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "token_hash", nullable = false, unique = true)
	private String tokenHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	protected PendingRegistration() {
	}

	PendingRegistration(UUID id, String email, String passwordHash, String tokenHash, Instant createdAt,
			Instant expiresAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.tokenHash = tokenHash;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	String getEmail() {
		return email;
	}

	String getPasswordHash() {
		return passwordHash;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}

	/**
	 * Ponowna rejestracja na ten sam adres wydaje nowy link i unieważnia poprzedni.
	 *
	 * <p>
	 * Hasła celowo <b>nie</b> podmieniamy. Gdyby podmieniała, ktoś obcy mógłby
	 * wstrzelić własne hasło w cudze, jeszcze niepotwierdzone zgłoszenie i czekać,
	 * aż prawowity właściciel skrzynki kliknie najnowszy link — konto powstałoby z
	 * hasłem napastnika. Kto pomylił się przy pierwszej rejestracji, potwierdza
	 * adres i zmienia hasło zwykłą drogą.
	 */
	void reissue(String newTokenHash, Instant now, Instant newExpiresAt) {
		this.tokenHash = newTokenHash;
		this.createdAt = now;
		this.expiresAt = newExpiresAt;
	}
}
