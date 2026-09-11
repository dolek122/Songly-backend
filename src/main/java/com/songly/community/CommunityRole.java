package com.songly.community;

import java.util.Set;

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

	/**
	 * Role z uprawnieniami moderacyjnymi. Jedno miejsce, bo ta sama lista odpowiada
	 * na dwa pytania — „czy ten człowiek moderuje" i „kogo zawiadomić o zgłoszeniu"
	 * — a rozjazd między nimi objawiłby się jako moderator bez powiadomień albo
	 * powiadomienia dla kogoś, kto nic nie może z nimi zrobić.
	 */
	static final Set<CommunityRole> MODERATING = Set.of(OWNER, MODERATOR);

	boolean canModerate() {
		return MODERATING.contains(this);
	}
}
