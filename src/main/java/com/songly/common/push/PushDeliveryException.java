package com.songly.common.push;

public class PushDeliveryException extends RuntimeException {

	public PushDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}

	public PushDeliveryException(String message) {
		super(message);
	}
}
