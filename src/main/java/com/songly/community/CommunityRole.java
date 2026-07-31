package com.songly.community;

/**
 * Rola w społeczności.
 *
 * <p>
 * {@code MODERATOR} istnieje po to, żeby moderacja przestała być wąskim gardłem
 * jednej osoby. Właściciel dalej jest jeden i tylko on rozdaje role — inaczej
 * moderator mógłby zdegradować właściciela i przejąć społeczność.
 */
public enum CommunityRole {

	OWNER,

	MODERATOR,

	MEMBER;

	boolean canModerate() {
		return this == OWNER || this == MODERATOR;
	}
}
