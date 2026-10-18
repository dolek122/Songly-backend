package com.songly.common.push;

/**
 * Kanał push (ADR 0040) — infrastruktura bez wiedzy o domenie (Claude.md §3,
 * reguła 3): dostaje gotowy tekst i token urządzenia, nie wie, czym jest
 * powiadomienie ani użytkownik.
 */
public interface PushSender {

	/**
	 * @throws InvalidPushTokenException
	 *             gdy provider mówi, że tego urządzenia już nie ma — wołający ma
	 *             wtedy usunąć token, inaczej każdy kolejny push trafiałby w
	 *             próżnię
	 * @throws PushDeliveryException
	 *             przy każdej innej porażce wysyłki
	 */
	void send(PushMessage message);
}
