package com.songly.profile;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Za dużo identyfikatorów w jednym zapytaniu o nazwy (ADR 0036). Wykazy, które
 * to wywołują, mają po kilka–kilkadziesiąt pozycji; limit chroni przed
 * zamienieniem endpointu w zrzut całej tabeli profili jednym żądaniem.
 */
class TooManyUserIdsException extends ApiException {

	TooManyUserIdsException(int limit) {
		super(HttpStatus.BAD_REQUEST, "TOO_MANY_USER_IDS", "Można zapytać o najwyżej " + limit + " użytkowników naraz");
	}
}
