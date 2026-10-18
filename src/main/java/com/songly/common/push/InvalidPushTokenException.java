package com.songly.common.push;

/**
 * Provider nie zna już tego tokenu (aplikacja odinstalowana, token wymieniony).
 */
public class InvalidPushTokenException extends RuntimeException {

	public InvalidPushTokenException(String message) {
		super(message);
	}
}
