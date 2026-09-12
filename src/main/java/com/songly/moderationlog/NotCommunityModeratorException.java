package com.songly.moderationlog;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Historię społeczności czyta jej moderacja. Nie-moderator dostaje 403, nie
 * 404: istnienie społeczności jest publiczne (§8), więc nie ma tu czego ukrywać
 * — inaczej niż przy cudzych zasobach prywatnych.
 */
class NotCommunityModeratorException extends ApiException {

	NotCommunityModeratorException() {
		super(HttpStatus.FORBIDDEN, "NOT_COMMUNITY_MODERATOR", "Tylko moderator społeczności może wykonać tę akcję");
	}
}
