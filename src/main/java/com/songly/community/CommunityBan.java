package com.songly.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

	protected CommunityBan() {
	}

	CommunityBan(UUID communityId, UUID userId, UUID bannedBy, String reason, Instant createdAt) {
		this.communityId = communityId;
		this.userId = userId;
		this.bannedBy = bannedBy;
		this.reason = reason;
		this.createdAt = createdAt;
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

}
