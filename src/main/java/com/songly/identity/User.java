package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
class User {

	@Id
	private UUID id;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "is_admin", nullable = false)
	private boolean admin;

	/** {@code null} = konto aktywne (ADR 0022). */
	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspension_reason", length = 500)
	private String suspensionReason;

	/** {@code null} przy trwającym zawieszeniu = bezterminowe. */
	@Column(name = "suspension_expires_at")
	private Instant suspensionExpiresAt;

	/** Odwołanie od zawieszenia (ADR 0023); {@code null} = nie odwołano się. */
	@Column(name = "suspension_appeal_text", length = 1000)
	private String suspensionAppealText;

	@Column(name = "suspension_appealed_at")
	private Instant suspensionAppealedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "suspension_appeal_status", length = 20)
	private SuspensionAppealStatus suspensionAppealStatus;

	protected User() {
	}

	User(UUID id, String email, String passwordHash, Instant createdAt) {
		this.id = id;
		this.email = email;
		this.passwordHash = passwordHash;
		this.createdAt = createdAt;
		this.admin = false;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isAdmin() {
		return admin;
	}

	void changePassword(String newPasswordHash) {
		this.passwordHash = newPasswordHash;
	}

	/**
	 * Zawieszenie konta (ADR 0022). {@code expiresAt} {@code null} = bezterminowo.
	 * Nadpisuje istniejące zawieszenie w całości — zmiana powodu albo terminu to ta
	 * sama decyzja podjęta na nowo, w odróżnieniu od banu społecznościowego, gdzie
	 * aktywny wiersz jest nietykalny (ADR 0014). Powód: zawiesza administrator,
	 * więc nie ma tu wyścigu dwóch moderatorów, przed którym ta reguła chroniła.
	 */
	void suspend(String reason, Instant at, Instant expiresAt) {
		this.suspendedAt = at;
		this.suspensionReason = reason;
		this.suspensionExpiresAt = expiresAt;
	}

	void liftSuspension() {
		this.suspendedAt = null;
		this.suspensionReason = null;
		this.suspensionExpiresAt = null;
		resetAppeal();
	}

	/**
	 * Czyści odwołanie (ADR 0023). Wołane przy zdjęciu zawieszenia oraz przy
	 * <b>nowym</b> zawieszeniu konta aktywnego — nowa sprawa daje świeże prawo do
	 * odwołania, nawet po wcześniejszym odrzuceniu.
	 *
	 * <p>
	 * Celowo <b>nie</b> przy poprawianiu powodu albo terminu trwającego
	 * zawieszenia: to ta sama sprawa, a wyczyszczenie pól skasowałoby po cichu
	 * złożoną petycję.
	 */
	void resetAppeal() {
		this.suspensionAppealText = null;
		this.suspensionAppealedAt = null;
		this.suspensionAppealStatus = null;
	}

	/**
	 * Jedno odwołanie na zawieszenie — drugie się nie zdarza, niezależnie od losu
	 * pierwszego ({@code hasAppeal} pilnuje tego u wołającego).
	 */
	void submitAppeal(String text, Instant at) {
		this.suspensionAppealText = text;
		this.suspensionAppealedAt = at;
		this.suspensionAppealStatus = SuspensionAppealStatus.PENDING;
	}

	void rejectAppeal() {
		this.suspensionAppealStatus = SuspensionAppealStatus.REJECTED;
	}

	boolean hasAppeal() {
		return suspensionAppealStatus != null;
	}

	boolean hasPendingAppeal() {
		return suspensionAppealStatus == SuspensionAppealStatus.PENDING;
	}

	String getSuspensionAppealText() {
		return suspensionAppealText;
	}

	Instant getSuspensionAppealedAt() {
		return suspensionAppealedAt;
	}

	SuspensionAppealStatus getSuspensionAppealStatus() {
		return suspensionAppealStatus;
	}

	/**
	 * Wygasanie jest leniwe — wiersz zostaje, a zawieszenie po terminie po prostu
	 * przestaje się liczyć (ADR 0014).
	 */
	public boolean isSuspendedAt(Instant now) {
		return suspendedAt != null && (suspensionExpiresAt == null || suspensionExpiresAt.isAfter(now));
	}

	Instant getSuspendedAt() {
		return suspendedAt;
	}

	String getSuspensionReason() {
		return suspensionReason;
	}

	Instant getSuspensionExpiresAt() {
		return suspensionExpiresAt;
	}

	/**
	 * Zmiana roli globalnej (ADR 0021). Bez settera na pole — nadanie i odebranie
	 * uprawnień administratora to dwie nazwane operacje, a nie ustawienie booleana:
	 * to jedyne miejsce w kodzie, przez które przechodzi eskalacja uprawnień, i ma
	 * być widoczne w wyszukiwaniu po nazwie.
	 */
	void changeAdmin(boolean admin) {
		this.admin = admin;
	}
}
