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

	@Column(name = "suspended_at")
	private Instant suspendedAt;

	@Column(name = "suspension_reason", length = 500)
	private String suspensionReason;

	@Column(name = "suspension_expires_at")
	private Instant suspensionExpiresAt;

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

	void resetAppeal() {
		this.suspensionAppealText = null;
		this.suspensionAppealedAt = null;
		this.suspensionAppealStatus = null;
	}

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

	void changeAdmin(boolean admin) {
		this.admin = admin;
	}
}
