package com.songly.common.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggingPushSender implements PushSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

	@Override
	public void send(PushMessage message) {
		String token = message.token();
		log.info("[PUSH:log] urządzenie={}… typ={}", token.substring(0, Math.min(8, token.length())),
				message.data().get("type"));
	}
}
