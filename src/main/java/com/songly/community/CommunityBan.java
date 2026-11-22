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

	@Column(name = "banned_by")
	private UUID bannedBy;

	@Column(length = 500)
	private String reason;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "appeal_text", length = 1000)
	private String appealText;

	@Column(name = "appealed_at")
	private Instant appealedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "appeal_status", length = 20)
	private BanAppealStatus appealStatus;

	@Column(name = "appeal_escalated_at")
	private Instant appealEscalatedAt;

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

	boolean isActiveAt(Instant now) {
		return expiresAt == null || expiresAt.isAfter(now);
	}

	void reimpose(UUID bannedBy, String reason, Instant createdAt, Instant expiresAt) {
		this.bannedBy = bannedBy;
		this.reason = reason;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
		this.appealText = null;
		this.appealedAt = null;
		this.appealStatus = null;
		this.appealEscalatedAt = null;
	}

	void submitAppeal(String text, Instant at) {
		this.appealText = text;
		this.appealedAt = at;
		this.appealStatus = BanAppealStatus.PENDING;
	}

	void rejectAppeal() {
		this.appealStatus = BanAppealStatus.REJECTED;
	}

	void escalateAppeal(Instant at) {
		this.appealStatus = BanAppealStatus.ESCALATED;
		this.appealEscalatedAt = at;
	}

	void rejectEscalation() {
		this.appealStatus = BanAppealStatus.ESCALATION_REJECTED;
	}

	boolean hasPendingAppeal() {
		return appealStatus == BanAppealStatus.PENDING;
	}

	boolean hasRejectedAppeal() {
		return appealStatus == BanAppealStatus.REJECTED;
	}

	boolean hasEscalatedAppeal() {
		return appealStatus == BanAppealStatus.ESCALATED;
	}

	boolean hasAppeal() {
		return appealStatus != null;
	}

	Instant getAppealEscalatedAt() {
		return appealEscalatedAt;
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
