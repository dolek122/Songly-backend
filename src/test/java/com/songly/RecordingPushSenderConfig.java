package com.songly;

import com.songly.common.push.InvalidPushTokenException;
import com.songly.common.push.PushMessage;
import com.songly.common.push.PushSender;
import java.time.Duration;
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
		private volatile Duration delay = Duration.ZERO;

		@Override
		public void send(PushMessage message) {
			if (!delay.isZero()) {
				sleep(delay);
			}
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

		/** Udaje wolny serwer FCM. Test, który to ustawia, musi przywrócić zero. */
		public void delayEachSendBy(Duration delay) {
			this.delay = delay;
		}

		private static void sleep(Duration duration) {
			try {
				Thread.sleep(duration.toMillis());
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Bean
	@Primary
	RecordingPushSender recordingPushSender() {
		return new RecordingPushSender();
	}
}
