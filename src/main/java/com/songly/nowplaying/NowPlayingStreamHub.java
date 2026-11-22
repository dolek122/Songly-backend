package com.songly.nowplaying;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class NowPlayingStreamHub {

	private static final Logger log = LoggerFactory.getLogger(NowPlayingStreamHub.class);
	private static final String ACTIVE_STREAMS_METRIC = "songly.nowplaying.streams.active";
	private static final String STREAM_EVENTS_METRIC = "songly.nowplaying.stream.events";

	private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();
	private final NowPlayingProperties properties;
	private final MeterRegistry meterRegistry;

	NowPlayingStreamHub(NowPlayingProperties properties, MeterRegistry meterRegistry) {
		this.properties = properties;
		this.meterRegistry = meterRegistry;
		Gauge.builder(ACTIVE_STREAMS_METRIC, this, NowPlayingStreamHub::openStreamCount)
				.description("Otwarte strumienie SSE presence na tej instancji").register(meterRegistry);
	}

	SseEmitter subscribe(UUID userId) {
		return register(userId, new SseEmitter(properties.getStream().getTimeout().toMillis()));
	}

	SseEmitter register(UUID userId, SseEmitter emitter) {
		emitters.compute(userId, (key, existing) -> {
			Set<SseEmitter> userEmitters = existing != null ? existing : ConcurrentHashMap.newKeySet();
			if (userEmitters.size() >= properties.getStream().getMaxPerUser()) {
				throw new TooManyStreamsException(properties.getStream().getMaxPerUser());
			}
			userEmitters.add(emitter);
			return userEmitters;
		});
		emitter.onCompletion(() -> remove(userId, emitter));
		emitter.onTimeout(() -> {
			emitter.complete();
			remove(userId, emitter);
		});
		emitter.onError(error -> remove(userId, emitter));
		return emitter;
	}

	void push(UUID userId, String eventName, Object payload) {
		send(userId, SseEmitter.event().name(eventName).data(payload));
	}

	void heartbeat() {
		for (UUID userId : Set.copyOf(emitters.keySet())) {
			send(userId, SseEmitter.event().comment("keep-alive"));
		}
	}

	private void send(UUID userId, SseEmitter.SseEventBuilder event) {
		Set<SseEmitter> userEmitters = emitters.get(userId);
		if (userEmitters == null) {
			return;
		}
		for (SseEmitter emitter : userEmitters) {
			try {
				emitter.send(event);
				meterRegistry.counter(STREAM_EVENTS_METRIC, "outcome", "delivered").increment();
			} catch (IOException | IllegalStateException ex) {
				log.debug("Usuwam martwy strumień SSE dla {}: {}", userId, ex.toString());
				meterRegistry.counter(STREAM_EVENTS_METRIC, "outcome", "dropped").increment();
				remove(userId, emitter);
			}
		}
	}

	void closeAll(UUID userId) {
		Set<SseEmitter> userEmitters = emitters.remove(userId);
		if (userEmitters != null) {
			userEmitters.forEach(SseEmitter::complete);
		}
	}

	boolean hasSubscribers(UUID userId) {
		return emitters.containsKey(userId);
	}

	boolean hasAnySubscriber() {
		return !emitters.isEmpty();
	}

	Set<UUID> connectedUserIds() {
		return Set.copyOf(emitters.keySet());
	}

	int openStreamCount() {
		return emitters.values().stream().mapToInt(Set::size).sum();
	}

	private void remove(UUID userId, SseEmitter emitter) {
		emitters.computeIfPresent(userId, (key, set) -> {
			set.remove(emitter);
			return set.isEmpty() ? null : set;
		});
	}
}
