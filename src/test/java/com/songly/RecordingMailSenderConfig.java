package com.songly;

import com.songly.common.mail.MailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

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
