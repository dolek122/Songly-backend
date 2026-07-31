package com.songly.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "profiles")
class Profile {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false, unique = true)
	private UUID userId;

	@Column(name = "display_name", nullable = false, length = 80)
	private String displayName;

	@Column(length = 500)
	private String bio;

	@Column(name = "avatar_key", length = 512)
	private String avatarKey;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Profile() {
	}

	Profile(UUID id, UUID userId, String displayName, String bio, String avatarKey, Instant createdAt,
			Instant updatedAt) {
		this.id = id;
		this.userId = userId;
		this.displayName = displayName;
		this.bio = bio;
		this.avatarKey = avatarKey;
		this.createdAt = createdAt;
		this.updatedAt = updatedAt;
	}

	void update(String displayName, String bio) {
		this.displayName = displayName;
		this.bio = bio;
		this.updatedAt = Instant.now();
	}

	/**
	 * Podmiana avatara jest osobną operacją od edycji profilu, bo wiąże się z
	 * plikiem w magazynie obiektow: wolajacy musi wiedziec, ktory klucz przestal
	 * byc uzywany, zeby go posprzatac.
	 */
	void changeAvatarKey(String avatarKey) {
		this.avatarKey = avatarKey;
		this.updatedAt = Instant.now();
	}

	UUID getUserId() {
		return userId;
	}

	String getDisplayName() {
		return displayName;
	}

	String getBio() {
		return bio;
	}

	String getAvatarKey() {
		return avatarKey;
	}
}
