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
	 * Wybór implementacji na podstawie {@code songly.mail.provider}.
	 *
	 * <p>
	 * {@code smtp} bez skonfigurowanego {@code spring.mail.host} kończy się
	 * <b>błędem startu</b>, a nie cichym zejściem do logowania. Instancja
	 * produkcyjna, która wystartowała i tylko wygląda, jakby wysyłała maile, jest
	 * dokładnie tą awarią, której nikt nie zauważa aż do pierwszego zgłoszenia „nie
	 * dostałem linku".
	 */
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
		return new SmtpMailSender(delegate, properties.getFrom());
	}
}
