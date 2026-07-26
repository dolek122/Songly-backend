package com.songly.content;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Czytać posty społeczności może każdy — publikować tylko członek. Dołączenie
 * jest otwarte i natychmiastowe, więc to nie bariera, a jedynie wymóg, żeby
 * pisanie w społeczności było świadomą decyzją.
 */
class NotCommunityMemberException extends ApiException {

	NotCommunityMemberException() {
		super(HttpStatus.FORBIDDEN, "NOT_COMMUNITY_MEMBER", "Tylko członek społeczności może w niej publikować");
	}
}
