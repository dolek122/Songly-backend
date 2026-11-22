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
