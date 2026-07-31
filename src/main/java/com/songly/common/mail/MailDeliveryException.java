package com.songly.common.mail;

/**
 * Wysyłka nie doszła do skutku. Celowo <b>nie</b> dziedziczy po
 * {@code ApiException}: awaria serwera pocztowego to nie jest błąd żądania
 * użytkownika i nie ma jednej sensownej odpowiedzi HTTP — tę decyzję podejmuje
 * wołający, w kontekście własnej operacji.
 */
public class MailDeliveryException extends RuntimeException {

	public MailDeliveryException(String message, Throwable cause) {
		super(message, cause);
	}
}
