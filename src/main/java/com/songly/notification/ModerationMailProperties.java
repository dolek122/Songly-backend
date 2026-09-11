package com.songly.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.notification.moderation-mail")
class ModerationMailProperties {

	private Duration moderatorInterval = Duration.ofMinutes(15);

	/**
	 * Odstęp między mailami <b>rozsyłanymi do wszystkich moderatorów</b> jednej
	 * społeczności (ADR 0018). Dotyczy zgłoszeń i odwołań — czyli tych dwóch
	 * wiadomości, które wyzwala ktoś z zewnątrz moderacji.
	 *
	 * <p>
	 * Limit per zgłaszający nie wystarcza, bo chroni przed jedną osobą, a nie przed
	 * dziesięcioma umówionymi: ofiarą zasypywania jest jedna skrzynka niezależnie
	 * od tego, ilu nadawców ją zapełnia (ta sama logika co {@code resendInterval}
	 * przy resecie hasła, Claude.md §9). Wyciszamy wyłącznie pocztę — kolejka i
	 * powiadomienie w aplikacji zostają, bo tam leży treść i tam moderator i tak
	 * musi przyjść.
	 */
	Duration getModeratorInterval() {
		return moderatorInterval;
	}

	void setModeratorInterval(Duration moderatorInterval) {
		this.moderatorInterval = moderatorInterval;
	}
}
