package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.songly.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"server.forward-headers-strategy=native", "songly.security.rate-limit.limit=2"})
class ForwardedClientAddressIntegrationTest extends AbstractIntegrationTest {

	private final HttpClient http = HttpClient.newHttpClient();

	@LocalServerPort
	private int port;

	@Test
	void spoofedAddresses_doNotOpenFreshRateLimitWindows() throws Exception {
		List<Integer> statuses = new ArrayList<>();
		for (int i = 1; i <= 3; i++) {
			statuses.add(refreshFrom("198.51.100." + i + ", 203.0.113.10", "for=192.0.2." + i));
		}

		assertThat(statuses).containsExactly(401, 401, 429);
	}

	@Test
	void clientsBehindProxy_haveSeparateWindows() throws Exception {
		assertThat(refreshFrom("203.0.113.20", null)).isEqualTo(401);
		assertThat(refreshFrom("203.0.113.20", null)).isEqualTo(401);

		assertThat(refreshFrom("203.0.113.21", null)).isEqualTo(401);
		assertThat(refreshFrom("203.0.113.20", null)).isEqualTo(429);
	}

	private int refreshFrom(String forwardedFor, String forwarded) throws Exception {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/refresh"))
				.header("Content-Type", "application/json").header("X-Forwarded-For", forwardedFor)
				.POST(HttpRequest.BodyPublishers.ofString("""
						{"refreshToken":"nie-ma-takiego"}"""));
		if (forwarded != null) {
			request.header("Forwarded", forwarded);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
	}
}
