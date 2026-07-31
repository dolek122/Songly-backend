package com.songly.identity;

import com.songly.common.mail.MailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Podstawia atrapę kanału e-mail, z której test odczytuje realnie wysłaną
 * wiadomość.
 *
 * <p>
 * Token resetu <b>nie jest nigdzie indziej dostępny w postaci jawnej</b> — w
 * bazie leży wyłącznie jego skrót — więc test, który nie czyta go z maila,
 * musiałby albo sięgnąć do wnętrza implementacji, albo w ogóle nie sprawdzać
 * ścieżki, którą przechodzi użytkownik. Atrapa jest tu więc jedyną drogą do
 * przetestowania tego, co się naprawdę liczy.
 *
 * <p>
 * {@code @Primary}, a nie nadpisanie definicji beana: wersja produkcyjna z
 * {@code MailConfig} zostaje w kontekście, więc test nadal weryfikuje, że
 * tamten bean w ogóle daje się utworzyć.
 */
@TestConfiguration
class RecordingMailSenderConfig {

	record SentMail(String to, String subject, String body) {
	}

	static class RecordingMailSender implements MailSender {

		private final List<SentMail> sent = new CopyOnWriteArrayList<>();

		@Override
		public void send(String to, String subject, String body) {
			sent.add(new SentMail(to, subject, body));
		}

		List<SentMail> sent() {
			return List.copyOf(sent);
		}

		SentMail lastTo(String recipient) {
			return sent.stream().filter(mail -> mail.to().equals(recipient)).reduce((first, second) -> second)
					.orElseThrow(() -> new AssertionError("Brak wiadomości do " + recipient));
		}

		void clear() {
			sent.clear();
		}
	}

	@Bean
	@Primary
	RecordingMailSender recordingMailSender() {
		return new RecordingMailSender();
	}
}
