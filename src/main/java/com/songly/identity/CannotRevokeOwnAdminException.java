package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Administrator nie odbiera uprawnień samemu sobie (ADR 0021).
 *
 * <p>
 * Ten zakaz nie jest ostrożnością wobec pomyłki, tylko <b>niezmiennikiem</b>:
 * skoro nikt nie może zdegradować siebie, a degradować może wyłącznie
 * administrator, to w systemie zawsze zostaje co najmniej jeden — i nigdy nie
 * trzeba wracać do ręcznego {@code UPDATE} w bazie, po to, żeby odzyskać
 * jakikolwiek dostęp administracyjny. Ta sama figura co „właściciela
 * społeczności nie da się zdegradować, także własną ręką" (ADR 0011).
 */
class CannotRevokeOwnAdminException extends ApiException {

	CannotRevokeOwnAdminException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_REVOKE_OWN_ADMIN",
				"Nie można odebrać uprawnień administratora samemu sobie");
	}
}
