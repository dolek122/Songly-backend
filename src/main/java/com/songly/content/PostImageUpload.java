package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Zdjęcie wgrane, ale jeszcze nieprzypięte do posta (ADR 0048).
 *
 * <p>
 * Wiersz istnieje dokładnie tak długo, jak plik czeka na post: publikacja posta
 * go zużywa, a sprzątanie usuwa porzucone. Klucz przypięty do posta nie ma już
 * wiersza, więc ani sprzątanie, ani limit oczekujących nie mają jak dotknąć
 * zdjęcia, które ktoś opublikował.
 */
@Entity
@Table(name = "post_image_uploads")
class PostImageUpload {

	@Id
	@Column(name = "image_key")
	private String imageKey;

	@Column(name = "owner_id", nullable = false)
	private UUID ownerId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PostImageUpload() {
	}

	PostImageUpload(String imageKey, UUID ownerId, Instant createdAt) {
		this.imageKey = imageKey;
		this.ownerId = ownerId;
		this.createdAt = createdAt;
	}

	String getImageKey() {
		return imageKey;
	}
}
