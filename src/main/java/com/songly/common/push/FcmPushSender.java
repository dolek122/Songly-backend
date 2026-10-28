package com.songly.common.push;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Wysyłka przez FCM HTTP v1 (ADR 0040). Jedno żądanie na urządzenie — API v1
 * nie ma wysyłki zbiorczej, a adresatów jednego powiadomienia jest zwykle
 * kilku.
 *
 * <p>
 * Android dostaje wiadomość wyłącznie z {@code data}, więc aplikacja sama
 * buduje powiadomienie (kanał, akcja po stuknięciu) także w tle. iOS bez bloku
 * {@code alert} nie pokazałby niczego, gdy aplikacja śpi — stąd osobny
 * {@code apns}, który na Androida nie wpływa.
 */
class FcmPushSender implements PushSender {

	private final RestClient restClient;
	private final String sendPath;
	private final Supplier<String> accessToken;

	FcmPushSender(RestClient restClient, String projectId, Supplier<String> accessToken) {
		this.restClient = restClient;
		this.sendPath = "/v1/projects/%s/messages:send".formatted(projectId);
		this.accessToken = accessToken;
	}

	@Override
	public void send(PushMessage message) {
		Map<String, Object> body = Map.of("message", Map.of("token", message.token(), "data", message.data(), "android",
				Map.of("priority", "high"), "apns", Map.of("payload",
						Map.of("aps", Map.of("alert", Map.of("title", message.title(), "body", message.body()))))));
		try {
			restClient.post().uri(sendPath).header("Authorization", "Bearer " + accessToken.get())
					.contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
					.onStatus(HttpStatusCode::isError, (request, response) -> {
						throw failure(response.getStatusCode(), readBody(response.getBody()));
					}).toBodilessEntity();
		} catch (ResourceAccessException ex) {
			throw new PushDeliveryException("FCM nie odpowiedział", ex);
		}
	}

	/**
	 * {@code UNREGISTERED} (zwykle z 404) to jedyny błąd, który na pewno mówi o
	 * samym tokenie. {@code INVALID_ARGUMENT} bywa i złym tokenem, i złym żądaniem
	 * — kasowanie tokenu na tej podstawie mogłoby po cichu odpiąć wszystkie
	 * urządzenia po jednym błędzie w kodzie, więc traktujemy go jak zwykłą porażkę.
	 *
	 * <p>
	 * Z tego samego powodu <b>sam status 404 nie wystarcza</b> (ADR 0049): zły
	 * adres FCM albo identyfikator projektu w konfiguracji też daje 404, a wtedy
	 * każdy push kasowałby telefon swojego adresata. FCM przy nieaktualnym tokenie
	 * zawsze dokłada kod {@code UNREGISTERED}.
	 */
	private static RuntimeException failure(HttpStatusCode status, String body) {
		if (body.contains("UNREGISTERED")) {
			return new InvalidPushTokenException("FCM nie zna tego urządzenia");
		}
		return new PushDeliveryException("FCM odrzucił wiadomość: HTTP " + status.value());
	}

	private static String readBody(InputStream stream) {
		try {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException ex) {
			return "";
		}
	}
}
