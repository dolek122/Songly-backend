package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Identyfikator żądania jest wart tyle, ile jego obecność w logach — więc
 * sprawdzamy nie samo pole w MDC, ale to, że dociera do zdarzenia logowania
 * wyprodukowanego głęboko w obsłudze żądania (Claude.md §13).
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class RequestIdLoggingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	private ListAppender<ILoggingEvent> appender;
	private Logger auditLogger;

	@BeforeEach
	void captureLogs() {
		appender = new ListAppender<>();
		appender.start();
		auditLogger = (Logger) LoggerFactory.getLogger("com.songly.identity.AuthService");
		auditLogger.addAppender(appender);
	}

	@AfterEach
	void releaseLogs() {
		auditLogger.detachAppender(appender);
		appender.stop();
	}

	private void failedLogin(String requestId) throws Exception {
		var builder = post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"nie-istnieje@songly.io","password":"password123"}""");
		if (requestId != null) {
			builder = builder.header(RequestIdFilter.REQUEST_ID_HEADER, requestId);
		}
		mockMvc.perform(builder).andExpect(status().isUnauthorized());
	}

	@Test
	void generatedRequestId_reachesResponseHeaderAndLogEvent() throws Exception {
		failedLogin(null);

		assertThat(appender.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage()).contains("Nieudane logowanie");
			// To jest właściwa asercja: linia logu z głębi obsługi żądania niesie
			// identyfikator, więc da się po nim zebrać wszystkie linie jednego żądania.
			assertThat(event.getMDCPropertyMap()).containsKey(RequestIdFilter.REQUEST_ID_KEY);
		});
	}

	@Test
	void incomingRequestId_isHonouredSoClientAndServerLogsMatch() throws Exception {
		failedLogin("mobile-abc-123");

		assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getMDCPropertyMap())
				.containsEntry(RequestIdFilter.REQUEST_ID_KEY, "mobile-abc-123"));
	}

	/**
	 * Nagłówek jest spod kontroli klienta, a ląduje w każdej linii logu — bez
	 * sanityzacji dałoby się wstrzyknąć do logów dowolną treść, w tym znaki nowej
	 * linii udające kolejne wpisy.
	 */
	@Test
	void hostileRequestId_isSanitized() throws Exception {
		failedLogin("abc\ndef ERROR wstrzykniete<>");

		assertThat(appender.list).singleElement()
				.satisfies(event -> assertThat(event.getMDCPropertyMap().get(RequestIdFilter.REQUEST_ID_KEY))
						.isEqualTo("abcdefERRORwstrzykniete"));
	}

	@Test
	void responseAlwaysCarriesRequestId() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"nie-istnieje@songly.io","password":"password123"}"""))
				.andExpect(header().exists(RequestIdFilter.REQUEST_ID_HEADER));
	}
}
