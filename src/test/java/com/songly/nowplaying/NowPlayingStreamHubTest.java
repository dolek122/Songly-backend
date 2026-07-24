package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

class NowPlayingStreamHubTest {

	private final NowPlayingStreamHub hub = new NowPlayingStreamHub();

	@Test
	void push_deliversToTargetUserOnly() throws IOException {
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();
		SseEmitter aliceEmitter = mock(SseEmitter.class);
		SseEmitter bobEmitter = mock(SseEmitter.class);
		hub.register(alice, aliceEmitter);
		hub.register(bob, bobEmitter);

		hub.push(alice, "now-playing", "payload");

		verify(aliceEmitter).send(any(SseEventBuilder.class));
		verify(bobEmitter, never()).send(any(SseEventBuilder.class));
	}

	@Test
	void push_toUserWithoutStream_isNoop() {
		hub.push(UUID.randomUUID(), "now-playing", "payload");
	}

	@Test
	void deadEmitter_isRemovedOnSendFailure() throws IOException {
		UUID user = UUID.randomUUID();
		SseEmitter emitter = mock(SseEmitter.class);
		doThrow(new IOException("zerwane")).when(emitter).send(any(SseEventBuilder.class));
		hub.register(user, emitter);
		assertThat(hub.hasSubscribers(user)).isTrue();

		hub.push(user, "now-playing", "payload");

		assertThat(hub.hasSubscribers(user)).isFalse();
	}

	@Test
	void connectedUserIds_reflectsRegisteredUsers() {
		UUID alice = UUID.randomUUID();
		hub.register(alice, mock(SseEmitter.class));

		assertThat(hub.connectedUserIds()).containsExactly(alice);
	}
}
