package com.songly.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Ulubiony gatunek użytkownika (N—M User—Genre przez {@code user_genres}). */
@Entity
@Table(name = "user_genres")
@IdClass(UserGenreId.class)
class UserGenre {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Id
	@Column(name = "genre_id")
	private UUID genreId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected UserGenre() {
	}

	UserGenre(UUID userId, UUID genreId, Instant createdAt) {
		this.userId = userId;
		this.genreId = genreId;
		this.createdAt = createdAt;
	}

	UUID getUserId() {
		return userId;
	}

	UUID getGenreId() {
		return genreId;
	}
}
