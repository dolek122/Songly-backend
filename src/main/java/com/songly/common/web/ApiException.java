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
}
