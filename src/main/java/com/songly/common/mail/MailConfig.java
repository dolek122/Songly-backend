package com.songly.common.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
class MailConfig {

	/**
	 * Dwa wątki wystarczą z zapasem: maile wychodzą przy rejestracji, resecie hasła
	 * i decyzjach moderacyjnych, a nie przy zwykłej aktywności.
	 */
	private static final int SMTP_THREADS = 2;

	private static final int SMTP_QUEUE_CAPACITY = 500;

	@Bean
	MailSender mailSender(MailProperties properties, ObjectProvider<JavaMailSender> javaMailSender) {
		if (!"smtp".equalsIgnoreCase(properties.getProvider())) {
			return new LoggingMailSender(properties.getFrom());
		}
		JavaMailSender delegate = javaMailSender.getIfAvailable();
		if (delegate == null) {
			throw new IllegalStateException(
					"songly.mail.provider=smtp wymaga skonfigurowanego spring.mail.host — bez tego aplikacja "
							+ "wystartowałaby bez działającej wysyłki e-maili (reset hasła przestałby działać po cichu)");
		}
		// Poza wątkiem żądania (ADR 0041). Kanał "log" zostaje synchroniczny: nie ma
		// czego mierzyć czasem, a testy czytają wiadomość zaraz po odpowiedzi.
		return new AsyncMailSender(new SmtpMailSender(delegate, properties.getFrom()), SMTP_THREADS,
				SMTP_QUEUE_CAPACITY);
	}
}
