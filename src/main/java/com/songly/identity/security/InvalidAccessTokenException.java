package com.songly.identity.security;

class InvalidAccessTokenException extends RuntimeException {

	InvalidAccessTokenException() {
		super("Access token jest nieprawidłowy lub wygasł");
	}
}
