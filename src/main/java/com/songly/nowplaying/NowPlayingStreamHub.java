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

/**
 * Rejestr otwartych strumieni SSE per użytkownik (ADR 0007, Etap 1). Trzyma
 * połączenia w pamięci instancji — fan-out między instancjami robi Redis
 * Pub/Sub (Etap 2), ale sam rejestr z natury pozostaje lokalny: emitter to
 * otwarte gniazdo tego konkretnego procesu.
 *
 * <p>
 * Stan ulotny: po restarcie instancji klienci się rozłączają i
 * auto-reconnectują (SSE robi to sam), więc nie ma potrzeby trwałości.
 *
 * <p>
 * Długo żyjące połączenia to zasób do pilnowania (Etap 4): liczba strumieni na
 * użytkownika jest limitowana, martwe połączenia wykrywa heartbeat, a stan
 * rejestru widać w metrykach Micrometera.
 */
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

	/**
	 * Otwiera nowy strumień dla użytkownika i rejestruje go do sprzątania po
	 * zamknięciu.
	 */
	SseEmitter subscribe(UUID userId) {
		return register(userId, new SseEmitter(properties.getStream().getTimeout().toMillis()));
	}

	/**
	 * Wydzielone z {@link #subscribe} dla testowalności (można podać atrapę
	 * emittera).
	 *
	 * @throws TooManyStreamsException
	 *             gdy użytkownik ma już maksymalną liczbę otwartych strumieni —
	 *             jeden klient nie może zająć instancji dowolną liczbą połączeń
	 */
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

	/**
	 * Wysyła zdarzenie do wszystkich otwartych strumieni użytkownika; no-op, gdy
	 * nie ma żadnego.
	 */
	void push(UUID userId, String eventName, Object payload) {
		send(userId, SseEmitter.event().name(eventName).data(payload));
	}

	/**
	 * Komentarz SSE bez treści zdarzenia — utrzymuje połączenie przy życiu przez
	 * proxy i pozwala wykryć zerwane gniazda, o których system operacyjny nie
	 * powiedział (wtedy dopiero zapis rzuca błędem).
	 */
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
				// Zerwane/zamknięte połączenie — usuwamy, klient sam się reconnectuje.
				log.debug("Usuwam martwy strumień SSE dla {}: {}", userId, ex.toString());
				meterRegistry.counter(STREAM_EVENTS_METRIC, "outcome", "dropped").increment();
				remove(userId, emitter);
			}
		}
	}

	/** Czy użytkownik ma choć jeden otwarty strumień (kogoś, kto go obserwuje). */
	boolean hasSubscribers(UUID userId) {
		return emitters.containsKey(userId);
	}

	/**
	 * Czy ta instancja trzyma jakikolwiek strumień — pozwala odrzucić wiadomość z
	 * kanału Redis (ADR 0007, Etap 2) bez zapytania o graf znajomych.
	 */
	boolean hasAnySubscriber() {
		return !emitters.isEmpty();
	}

	/**
	 * Użytkownicy z co najmniej jednym otwartym strumieniem — do serwerowego polla
	 * (Etap 1b).
	 */
	Set<UUID> connectedUserIds() {
		return Set.copyOf(emitters.keySet());
	}

	/** Liczba otwartych strumieni na tej instancji — źródło metryki. */
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
