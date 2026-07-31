package com.songly.community;

import java.util.UUID;

/**
 * Publiczna granica modułu community dla innych modułów (Claude.md §2): pytania
 * o istnienie społeczności i uprawnienia użytkownika, bez dostępu do encji
 * modułu.
 *
 * <p>
 * Powstał dla modułu content, który przechowuje posty przypisane do
 * społeczności i musi wiedzieć, kto ma prawo w niej pisać i moderować — ale nie
 * ma prawa zaglądać do tabeli członkostw.
 */
public interface CommunityMembership {

	boolean exists(UUID communityId);

	boolean isMember(UUID communityId, UUID userId);

	/** Właściciel moderuje treści w swojej społeczności (Claude.md §8). */
	boolean isOwner(UUID communityId, UUID userId);

	/**
	 * Czy użytkownik moderuje tę społeczność — właściciel albo moderator (ADR
	 * 0011).
	 *
	 * <p>
	 * Osobno od {@link #isOwner}, bo to dwa różne pytania i mylenie ich jest
	 * właśnie tym, przed czym ta metoda chroni: „kto może skasować cudzy post"
	 * (moderatorzy) to nie to samo co „kto może rozdawać role i skasować całą
	 * społeczność" (wyłącznie właściciel).
	 */
	boolean canModerate(UUID communityId, UUID userId);

	/**
	 * Czy użytkownik jest zbanowany. Moduł content pyta o to przed przyjęciem posta
	 * — samo sprawdzenie członkostwa nie wystarcza, bo ban usuwa członkostwo, a
	 * ponowne dołączenie jest jednym żądaniem.
	 */
	boolean isBanned(UUID communityId, UUID userId);
}
