package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import tools.jackson.databind.ObjectMapper;

/**
 * Odbiór wiadomości z kanału Redis (ADR 0007, Etap 2).
 */
@ExtendWith(MockitoExtension.class)
class NowPlayingStatusSubscriberTest {

	@Mock
	private NowPlayingStreamBroadcaster broadcaster;

	@Mock
	private Message message;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private NowPlayingStatusSubscriber subscriber() {
		return new NowPlayingStatusSubscriber(objectMapper, broadcaster);
	}

	@Test
	void handsParsedMessageToBroadcaster() {
		UUID userId = UUID.randomUUID();
		NowPlayingStatusResponse status = new NowPlayingStatusResponse("spotify", "Bohemian Rhapsody", "Queen",
				"A Night at the Opera", null, true, Instant.now());
		when(message.getBody())
				.thenReturn(objectMapper.writeValueAsString(new NowPlayingStatusChangedMessage(userId, status))
						.getBytes(StandardCharsets.UTF_8));

		subscriber().onMessage(message, null);

		ArgumentCaptor<NowPlayingStatusChangedMessage> received = ArgumentCaptor
				.forClass(NowPlayingStatusChangedMessage.class);
		verify(broadcaster).broadcast(received.capture());
		assertThat(received.getValue().userId()).isEqualTo(userId);
		assertThat(received.getValue().status().title()).isEqualTo("Bohemian Rhapsody");
	}

	/**
	 * Wiadomość w nieznanym kształcie (np. ze starszej instancji w trakcie rolling
	 * deployu) nie może wywrócić kontenera nasłuchującego — presence jest ulotne,
	 * więc porzucenie jednej zmiany jest nieszkodliwe.
	 */
	@Test
	void swallowsUnreadableMessage() {
		when(message.getBody()).thenReturn("{nie-json".getBytes(StandardCharsets.UTF_8));

		subscriber().onMessage(message, null);

		verifyNoInteractions(broadcaster);
	}

	@Test
	void swallowsMessageWithUnexpectedShape() {
		when(message.getBody()).thenReturn("{\"userId\":\"nie-uuid\"}".getBytes(StandardCharsets.UTF_8));

		subscriber().onMessage(message, null);

		verify(broadcaster, never()).broadcast(any());
	}
}
