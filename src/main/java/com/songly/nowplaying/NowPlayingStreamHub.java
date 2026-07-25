package com.songly.nowplaying;

import java.io.IOException;
import java.time.Duration;
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
 */
@Component
class NowPlayingStreamHub {

	private static final Logger log = LoggerFactory.getLogger(NowPlayingStreamHub.class);
	private static final long STREAM_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

	private final Map<UUID, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

	/**
	 * Otwiera nowy strumień dla użytkownika i rejestruje go do sprzątania po
	 * zamknięciu.
	 */
	SseEmitter subscribe(UUID userId) {
		return register(userId, new SseEmitter(STREAM_TIMEOUT_MS));
	}

	/**
	 * Wydzielone z {@link #subscribe} dla testowalności (można podać atrapę
	 * emittera).
	 */
	SseEmitter register(UUID userId, SseEmitter emitter) {
		emitters.computeIfAbsent(userId, key -> ConcurrentHashMap.newKeySet()).add(emitter);
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
		Set<SseEmitter> userEmitters = emitters.get(userId);
		if (userEmitters == null) {
			return;
		}
		for (SseEmitter emitter : userEmitters) {
			try {
				emitter.send(SseEmitter.event().name(eventName).data(payload));
			} catch (IOException | IllegalStateException ex) {
				// Zerwane/zamknięte połączenie — usuwamy, klient sam się reconnectuje.
				log.debug("Usuwam martwy strumień SSE dla {}: {}", userId, ex.toString());
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

	private void remove(UUID userId, SseEmitter emitter) {
		emitters.computeIfPresent(userId, (key, set) -> {
			set.remove(emitter);
			return set.isEmpty() ? null : set;
		});
	}
}
