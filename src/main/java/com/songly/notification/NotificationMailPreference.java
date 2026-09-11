package com.songly.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Preferencje kanału e-mail per użytkownik (ADR 0020). Wiersz zapisujemy
 * dopiero przy pierwszej zmianie — brak wiersza znaczy „obie kategorie
 * włączone", więc nie trzeba zakładać rekordu przy rejestracji (ten sam wzorzec
 * co {@code NowPlayingPreference}).
 */
@Entity
@Table(name = "notification_mail_preferences")
class NotificationMailPreference {

	@Id
	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "decisions_about_me", nullable = false)
	private boolean decisionsAboutMe;

	@Column(name = "moderation_queue", nullable = false)
	private boolean moderationQueue;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected NotificationMailPreference() {
	}

	NotificationMailPreference(UUID userId, boolean decisionsAboutMe, boolean moderationQueue, Instant updatedAt) {
		this.userId = userId;
		this.decisionsAboutMe = decisionsAboutMe;
		this.moderationQueue = moderationQueue;
		this.updatedAt = updatedAt;
	}

	UUID getUserId() {
		return userId;
	}

	boolean allows(MailCategory category) {
		return category == MailCategory.DECISION_ABOUT_ME ? decisionsAboutMe : moderationQueue;
	}

	boolean isDecisionsAboutMe() {
		return decisionsAboutMe;
	}

	boolean isModerationQueue() {
		return moderationQueue;
	}

	void update(boolean decisionsAboutMe, boolean moderationQueue, Instant now) {
		this.decisionsAboutMe = decisionsAboutMe;
		this.moderationQueue = moderationQueue;
		this.updatedAt = now;
	}
}
