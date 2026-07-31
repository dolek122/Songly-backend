package com.songly;

import com.songly.common.mail.MailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Podstawia atrapę kanału e-mail, z której testy odczytują realnie wysłane
 * wiadomości.
 *
 * <p>
 * Tokeny weryfikacji adresu i resetu hasła <b>nie są nigdzie indziej dostępne w
 * postaci jawnej</b> — w bazie leżą wyłącznie ich skróty. Test, który nie czyta
 * ich z wiadomości, musiałby albo sięgnąć do wnętrza implementacji, albo w
 * ogóle nie sprawdzać ścieżki, którą przechodzi użytkownik.
 *
 * <p>
 * {@code @Primary}, a nie nadpisanie definicji beana: wersja produkcyjna z
 * {@code MailConfig} zostaje w kontekście, więc testy nadal weryfikują, że
 * tamten bean w ogóle daje się utworzyć.
 *
 * <p>
 * Wpięte globalnie w {@link AbstractIntegrationTest}, bo od czasu weryfikacji
 * adresu (ADR 0009) przez wiadomość przechodzi każdy test potrzebujący
 * zalogowanego użytkownika.
 */
@TestConfiguration
public class RecordingMailSenderConfig {

	public record SentMail(String to, String subject, String body) {
	}

	public static class RecordingMailSender implements MailSender {

		private final List<SentMail> sent = new CopyOnWriteArrayList<>();

		@Override
		public void send(String to, String subject, String body) {
			sent.add(new SentMail(to, subject, body));
		}

		public List<SentMail> sent() {
			return List.copyOf(sent);
		}

		public List<SentMail> sentTo(String recipient) {
			return sent.stream().filter(mail -> mail.to().equals(recipient)).toList();
		}

		public SentMail lastTo(String recipient) {
			return sent.stream().filter(mail -> mail.to().equals(recipient)).reduce((first, second) -> second)
					.orElseThrow(() -> new AssertionError("Brak wiadomości do " + recipient));
		}

		public void clear() {
			sent.clear();
		}

		/**
		 * Czyści skrzynkę jednego adresata. Potrzebne, bo kontekst Springa (a z nim ten
		 * rekorder) jest współdzielony między metodami testowymi — bez tego
		 * {@link #lastTo} mogłoby trafić na wiadomość z poprzedniego przypadku.
		 */
		public void clearFor(String recipient) {
			sent.removeIf(mail -> mail.to().equals(recipient));
		}
	}

	@Bean
	@Primary
	RecordingMailSender recordingMailSender() {
		return new RecordingMailSender();
	}
}
