package com.songly.nowplaying;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Ustawienia widoczności presence per użytkownik (ADR 0007, Etap 4). Wiersz
 * zapisujemy dopiero przy pierwszej zmianie — brak wiersza to "domyślnie
 * widoczny dla znajomych", więc nie trzeba zakładać rekordu każdemu przy
 * rejestracji.
 */
@Entity
@Table(name = "now_playing_preferences")
class NowPlayingPreference {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "share_with_friends", nullable = false)
	private boolean shareWithFriends;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected NowPlayingPreference() {
	}

	NowPlayingPreference(UUID userId, boolean shareWithFriends, Instant updatedAt) {
		this.userId = userId;
		this.shareWithFriends = shareWithFriends;
		this.updatedAt = updatedAt;
	}

	boolean isShareWithFriends() {
		return shareWithFriends;
	}

	void updateShareWithFriends(boolean shareWithFriends, Instant now) {
		this.shareWithFriends = shareWithFriends;
		this.updatedAt = now;
	}
}
