package com.songly.moderationlog;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Nie ma takiej społeczności — sprawdzane przed uprawnieniami, bo inaczej 403
 * na nieistniejącym identyfikatorze sugerowałoby, że coś tam jest.
 */
class CommunityLogNotFoundException extends ApiException {

	CommunityLogNotFoundException() {
		super(HttpStatus.NOT_FOUND, "COMMUNITY_NOT_FOUND", "Nie ma takiej społeczności");
	}
}
