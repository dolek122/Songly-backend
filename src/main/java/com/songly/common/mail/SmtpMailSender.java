package com.songly.common.mail;

import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class SmtpMailSender implements MailSender {

	private final JavaMailSender javaMailSender;
	private final String from;

	SmtpMailSender(JavaMailSender javaMailSender, String from) {
		this.javaMailSender = javaMailSender;
		this.from = from;
	}

	@Override
	public void send(String to, String subject, String body) {
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(to);
		message.setSubject(subject);
		message.setText(body);
		try {
			javaMailSender.send(message);
		} catch (MailException ex) {
			throw new MailDeliveryException("Nie udało się wysłać wiadomości e-mail", ex);
		}
	}
}
