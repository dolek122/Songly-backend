package com.songly.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	private final String from;

	LoggingMailSender(String from) {
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) {
		log.info("[MAIL:log] od={} do={} temat={}\n{}", from, to, subject, body);
	}
}
