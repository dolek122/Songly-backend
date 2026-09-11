package com.songly.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
	 * Zmiana roli globalnej (ADR 0021). Bez settera na pole — nadanie i odebranie
	 * uprawnień administratora to dwie nazwane operacje, a nie ustawienie booleana:
	 * to jedyne miejsce w kodzie, przez które przechodzi eskalacja uprawnień, i ma
	 * być widoczne w wyszukiwaniu po nazwie.
	 */
	void changeAdmin(boolean admin) {
		this.admin = admin;
	}
}
