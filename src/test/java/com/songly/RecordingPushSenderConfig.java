package com.songly;

import com.songly.common.push.InvalidPushTokenException;
import com.songly.common.push.PushMessage;
import com.songly.common.push.PushSender;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Atrapa kanału push, z której testy odczytują realnie wysłane wiadomości (ADR
 * 0040). Ten sam układ co {@link RecordingMailSenderConfig}: {@code @Primary},
 * więc produkcyjny bean z {@code PushConfig} nadal musi dać się utworzyć, i
 * wpięte globalnie, żeby nie mnożyć kontekstów Springa.
 */
@TestConfiguration
public class RecordingPushSenderConfig {

	public static class RecordingPushSender implements PushSender {

		private final List<PushMessage> sent = new CopyOnWriteArrayList<>();
		private final Set<String> unregistered = ConcurrentHashMap.newKeySet();

		@Override
		public void send(PushMessage message) {
			if (unregistered.contains(message.token())) {
				throw new InvalidPushTokenException("test: urządzenie odinstalowane");
			}
			sent.add(message);
		}

		public List<PushMessage> sentTo(String token) {
			return sent.stream().filter(message -> message.token().equals(token)).toList();
		}

		/** Udaje FCM, który przestał znać ten token. */
		public void markUnregistered(String token) {
			unregistered.add(token);
		}
	}

	@Bean
	@Primary
	RecordingPushSender recordingPushSender() {
		return new RecordingPushSender();
	}
}
