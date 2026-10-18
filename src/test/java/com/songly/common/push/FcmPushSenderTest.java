package com.songly.common.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Mapowanie odpowiedzi FCM na wyjątki (ADR 0040) na prawdziwym HTTP, bez
 * Google'a: lokalny serwer udaje endpoint {@code messages:send}.
 *
 * <p>
 * Najważniejsza jest granica między „tokenu już nie ma" (wołający kasuje
 * urządzenie) a każdą inną porażką: pomyłka w tę stronę odpinałaby po cichu
 * zdrowe telefony.
 */
class FcmPushSenderTest {

	private HttpServer server;
	private final AtomicReference<String> lastBody = new AtomicReference<>();
	private final AtomicReference<String> lastAuth = new AtomicReference<>();
	private volatile int status = 200;
	private volatile String response = "{\"name\":\"projects/p/messages/1\"}";

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/projects/songly-test/messages:send", exchange -> {
			lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private FcmPushSender sender() {
		RestClient restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
				.build();
		return new FcmPushSender(restClient, "songly-test", () -> "access-token");
	}

	private static PushMessage message() {
		return new PushMessage("device-token", "Ola", "Polubienie Twojego posta", Map.of("type", "POST_LIKED"));
	}

	@Test
	void sendsDataOnlyForAndroidAndAlertForIos() {
		sender().send(message());

		assertThat(lastAuth.get()).isEqualTo("Bearer access-token");
		assertThat(lastBody.get()).contains("\"token\":\"device-token\"").contains("\"type\":\"POST_LIKED\"")
				.contains("\"priority\":\"high\"").contains("\"alert\"").contains("\"title\":\"Ola\"")
				// Blok "notification" FCM sprawiłby, że Android w tle pokazałby push sam,
				// z pominięciem kanału i akcji aplikacji.
				.doesNotContain("\"notification\"");
	}

	@Test
	void unregisteredTokenIsReportedAsInvalid() {
		status = 404;
		response = "{\"error\":{\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";

		assertThatThrownBy(() -> sender().send(message())).isInstanceOf(InvalidPushTokenException.class);
	}

	@Test
	void unregisteredInAnotherStatusIsStillInvalid() {
		status = 400;
		response = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";

		assertThatThrownBy(() -> sender().send(message())).isInstanceOf(InvalidPushTokenException.class);
	}

	/**
	 * INVALID_ARGUMENT bywa błędem żądania — nie wolno na tej podstawie kasować
	 * urządzenia.
	 */
	@Test
	void invalidArgumentIsAnOrdinaryFailure() {
		status = 400;
		response = "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"errorCode\":\"INVALID_ARGUMENT\"}]}}";

		assertThatThrownBy(() -> sender().send(message())).isInstanceOf(PushDeliveryException.class)
				.isNotInstanceOf(InvalidPushTokenException.class);
	}

	@Test
	void serverErrorIsAnOrdinaryFailure() {
		status = 503;
		response = "{\"error\":{\"status\":\"UNAVAILABLE\"}}";

		assertThatThrownBy(() -> sender().send(message())).isInstanceOf(PushDeliveryException.class);
	}
}
