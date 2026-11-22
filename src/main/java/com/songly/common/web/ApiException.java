package com.songly.common.web;

import org.springframework.http.HttpStatus;

/**
 * Bazowa wyjątek domenowy niosący status HTTP i kod błędu do {@link ApiError}.
 */
public abstract class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	protected ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	/**
	 * Za ile sekund wolno spróbować ponownie, dla odpowiedzi 429. {@code null} =
	 * bez nagłówka {@code Retry-After}. Bez niego klient nie wie, kiedy wrócić, i
	 * dobija się w kółko — czyli robi to, przed czym limit ma chronić.
	 */
	public Long getRetryAfterSeconds() {
		return null;
	}
}
