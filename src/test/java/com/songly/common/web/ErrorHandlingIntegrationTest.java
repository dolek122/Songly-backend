package com.songly.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * Błędne żądanie klienta ma dawać 4xx w formacie {@link ApiError} (Claude.md
 * §6), nie 500 — inaczej literówka w JSON-ie po stronie klienta wygląda w
 * monitoringu identycznie jak realna awaria serwera i topi ją w szumie.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ErrorHandlingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void malformedJsonBody_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{to nie jest json"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
				.andExpect(jsonPath("$.path").value("/api/v1/auth/login"));
	}

	@Test
	void missingBody_returnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	/** Publiczny callback Spotify bez parametrów, które dokleja provider. */
	@Test
	void missingRequiredRequestParameter_returnsBadRequest() throws Exception {
		mockMvc.perform(get("/api/v1/now-playing/spotify/callback")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
	}

	@Test
	void wrongHttpMethod_returnsMethodNotAllowedWithAllowHeader() throws Exception {
		mockMvc.perform(get("/api/v1/auth/login")).andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED")).andExpect(header().exists("Allow"));
	}

	@Test
	void validationError_stillReportsFieldDetails() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"to-nie-jest-email","password":"krotkie"}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	/**
	 * Uwierzytelnione żądanie do nieistniejącej ścieżki ma dawać 404 {@code
	 * NOT_FOUND}, nie 500 — literówka w URL-u klienta to błąd klienta, a nie awaria
	 * serwera (Claude.md §6). Anonimowe żądanie testujemy osobno, bo Security
	 * odcina je 401-ką jeszcze przed dispatcherem, więc nie dotknęłoby handlera.
	 */
	@Test
	void unknownRouteWhenAuthenticated_returnsNotFound() throws Exception {
		MvcResult registration = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"unknown-route@songly.io","password":"password123"}"""))
				.andExpect(status().isCreated()).andReturn();
		String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString()).get("accessToken")
				.asText();

		mockMvc.perform(get("/api/v1/nie-ma-takiej-sciezki").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.path").value("/api/v1/nie-ma-takiej-sciezki"));
	}

	@Test
	void unknownRouteWhenAnonymous_returnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/nie-ma-takiej-sciezki")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}
}
