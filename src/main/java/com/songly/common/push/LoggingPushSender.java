package com.songly.common.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domyślna implementacja dla dev i testów: wypisuje push do logu zamiast go
 * wysyłać, jak {@code LoggingMailSender}.
 *
 * <p>
 * W przeciwieństwie do maila <b>bez treści</b>: treść niesie nazwę innej osoby,
 * a do sprawdzenia, że push w ogóle wyszedł, wystarczy typ. Z tokenu tylko
 * początek — pełny token to adres doręczenia na czyjś telefon.
 */
class LoggingPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

	@Override
	public void send(PushMessage message) {
		String token = message.token();
		log.info("[PUSH:log] urządzenie={}… typ={}", token.substring(0, Math.min(8, token.length())),
				message.data().get("type"));
	}
}
