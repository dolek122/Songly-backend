package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

class NowPlayingStreamHubTest {

	private final NowPlayingProperties properties = new NowPlayingProperties();
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final NowPlayingStreamHub hub = new NowPlayingStreamHub(properties, meterRegistry);

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

	@Test
	void register_rejectsStreamAboveLimit_leavingEarlierOnesUntouched() {
		UUID user = UUID.randomUUID();
		properties.getStream().setMaxPerUser(2);
		hub.register(user, mock(SseEmitter.class));
		hub.register(user, mock(SseEmitter.class));

		assertThatThrownBy(() -> hub.register(user, mock(SseEmitter.class)))
				.isInstanceOf(TooManyStreamsException.class);
		assertThat(hub.openStreamCount()).isEqualTo(2);
	}

	@Test
	void limitIsPerUser_notGlobal() {
		properties.getStream().setMaxPerUser(1);
		hub.register(UUID.randomUUID(), mock(SseEmitter.class));

		hub.register(UUID.randomUUID(), mock(SseEmitter.class));

		assertThat(hub.openStreamCount()).isEqualTo(2);
	}

	@Test
	void heartbeat_reachesEveryStream_andCleansUpDeadOnes() throws IOException {
		UUID alive = UUID.randomUUID();
		UUID dead = UUID.randomUUID();
		SseEmitter aliveEmitter = mock(SseEmitter.class);
		SseEmitter deadEmitter = mock(SseEmitter.class);
		doThrow(new IOException("zerwane")).when(deadEmitter).send(any(SseEventBuilder.class));
		hub.register(alive, aliveEmitter);
		hub.register(dead, deadEmitter);

		hub.heartbeat();

		verify(aliveEmitter).send(any(SseEventBuilder.class));
		assertThat(hub.hasSubscribers(alive)).isTrue();
		// O zerwanym gnieździe dowiadujemy się dopiero przy próbie zapisu — po to jest
		// heartbeat, żeby taki strumień nie siedział w rejestrze do timeoutu.
		assertThat(hub.hasSubscribers(dead)).isFalse();
	}

	@Test
	void metricsReportOpenStreams() {
		hub.register(UUID.randomUUID(), mock(SseEmitter.class));

		assertThat(meterRegistry.get("songly.nowplaying.streams.active").gauge().value()).isEqualTo(1);
	}

	@Test
	void concurrentRegistrations_respectLimit_withoutLosingStreams() throws Exception {
		UUID user = UUID.randomUUID();
		properties.getStream().setMaxPerUser(10);
		int threads = 32;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		AtomicInteger accepted = new AtomicInteger();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		for (int i = 0; i < threads; i++) {
			pool.execute(() -> {
				try {
					start.await();
					hub.register(user, mock(SseEmitter.class));
					accepted.incrementAndGet();
				} catch (TooManyStreamsException ignored) {
					// oczekiwane dla nadmiarowych prób
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(accepted.get()).isEqualTo(10);
		assertThat(hub.openStreamCount()).isEqualTo(10);
	}
}
