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
}
