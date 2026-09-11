package com.songly.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Zakaz uczestnictwa w społeczności.
 *
 * <p>
 * Ban odcina od <b>pisania</b>, nie od czytania. Społeczności są w pełni
 * publiczne (Claude.md §8), więc ukrywanie treści przed zbanowanym byłoby
 * ochroną pozorną — wystarczy się wylogować. Realna szkoda idzie ze ścieżek
 * zapisu i to je odcinamy.
 */
@Entity
@Table(name = "community_bans")
@IdClass(CommunityBanId.class)
class CommunityBan {

	@Id
	@Column(name = "community_id", nullable = false)
	private UUID communityId;

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	/**
	 * {@code null} po skasowaniu konta moderatora, który ban nałożył. Ban należy do
	 * społeczności, nie do tej osoby — jej odejście nie może po cichu wpuszczać z
	 * powrotem wszystkich, których zbanowała.
	 */
	@Column(name = "banned_by")
	private UUID bannedBy;

	@Column(length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** {@code null} = ban bezterminowy (ADR 0014). */
	@Column(name = "expires_at")
	private Instant expiresAt;

	/** Treść odwołania (ADR 0017); {@code null} = brak odwołania. */
	@Column(name = "appeal_text", length = 1000)
	private String appealText;

	@Column(name = "appealed_at")
	private Instant appealedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "appeal_status", length = 20)
	private BanAppealStatus appealStatus;

	protected CommunityBan() {
	}

	CommunityBan(UUID communityId, UUID userId, UUID bannedBy, String reason, Instant createdAt, Instant expiresAt) {
		this.communityId = communityId;
		this.userId = userId;
		this.bannedBy = bannedBy;
		this.reason = reason;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	/**
	 * Wygasanie jest leniwe — nic nie dzieje się w chwili wygaśnięcia, po prostu
	 * każdy, kto pyta o ban, pyta o ban <i>aktywny</i>.
	 */
	boolean isActiveAt(Instant now) {
		return expiresAt == null || expiresAt.isAfter(now);
	}

	/**
	 * Ponowny ban po wygaśnięciu poprzedniego. Nadpisuje wiersz w całości, bo klucz
	 * główny (społeczność, użytkownik) dopuszcza tylko jeden — a wygasły ban to
	 * historia, nie stan. To <b>nowa decyzja moderacyjna</b>: wołający publikuje
	 * dla niej nowe zdarzenie, jak przy pierwszym banie.
	 *
	 * <p>
	 * Czyści też odwołanie (ADR 0017): dotyczyło poprzedniego bana, a nowy ban to
	 * nowa sprawa — łącznie z prawem do świeżego odwołania, nawet jeśli poprzednie
	 * odrzucono.
	 */
	void reimpose(UUID bannedBy, String reason, Instant createdAt, Instant expiresAt) {
		this.bannedBy = bannedBy;
		this.reason = reason;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.appealText = null;
		this.appealedAt = null;
		this.appealStatus = null;
	}

	/**
	 * Jedno odwołanie na ban — drugie się nie zdarza, niezależnie od losu
	 * pierwszego. Odrzucone odwołanie jest więc ostateczne dla tego bana; świeże
	 * prawo do odwołania daje dopiero nowy ban ({@link #reimpose}).
	 */
	void submitAppeal(String text, Instant at) {
		this.appealText = text;
		this.appealedAt = at;
		this.appealStatus = BanAppealStatus.PENDING;
	}

	void rejectAppeal() {
		this.appealStatus = BanAppealStatus.REJECTED;
	}

	boolean hasPendingAppeal() {
		return appealStatus == BanAppealStatus.PENDING;
	}

	boolean hasAppeal() {
		return appealStatus != null;
	}

	UUID getCommunityId() {
		return communityId;
	}

	UUID getUserId() {
		return userId;
	}

	UUID getBannedBy() {
		return bannedBy;
	}

	String getReason() {
		return reason;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	Instant getExpiresAt() {
		return expiresAt;
	}

	String getAppealText() {
		return appealText;
	}

	Instant getAppealedAt() {
		return appealedAt;
	}

	BanAppealStatus getAppealStatus() {
		return appealStatus;
	}

}
