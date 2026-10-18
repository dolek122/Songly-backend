package com.songly.common.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Ten sam układ co kanał e-mail (Claude.md §9): domyślnie {@code log}, a
 * {@code fcm} bez klucza konta serwisowego <b>wywala start</b> — instancja,
 * która tylko wygląda, jakby wysyłała pushe, to awaria niezauważalna, dopóki
 * ktoś nie zapyta, czemu telefon milczy.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
class PushConfig {

	private static final String FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

	@Bean
	PushSender pushSender(PushProperties properties) {
		if (!"fcm".equalsIgnoreCase(properties.getProvider())) {
			return new LoggingPushSender();
		}
		String json = properties.getFcmCredentialsJson();
		if (json == null || json.isBlank()) {
			throw new IllegalStateException("songly.push.provider=fcm wymaga songly.push.fcm-credentials-json — bez "
					+ "tego aplikacja wystartowałaby bez działającej wysyłki pushy");
		}
		ServiceAccountCredentials credentials = serviceAccount(json);
		GoogleCredentials scoped = credentials.createScoped(List.of(FCM_SCOPE));

		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		RestClient restClient = RestClient.builder().baseUrl(properties.getFcmBaseUrl()).requestFactory(requestFactory)
				.build();
		return new FcmPushSender(restClient, credentials.getProjectId(), () -> accessToken(scoped));
	}

	private static ServiceAccountCredentials serviceAccount(String json) {
		try {
			return ServiceAccountCredentials
					.fromStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
		} catch (IOException ex) {
			throw new IllegalStateException("songly.push.fcm-credentials-json nie jest kluczem konta serwisowego", ex);
		}
	}

	/** Biblioteka cache'uje token i odświeża go sama przed wygaśnięciem. */
	private static String accessToken(GoogleCredentials credentials) {
		try {
			credentials.refreshIfExpired();
			return credentials.getAccessToken().getTokenValue();
		} catch (IOException ex) {
			throw new PushDeliveryException("Nie udało się pobrać tokenu OAuth dla FCM", ex);
		}
	}
}
