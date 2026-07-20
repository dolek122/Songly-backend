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

/**
 * Błędne żądanie klienta ma dawać 4xx w formacie {@link ApiError} (Claude.md
 * §6), nie 500 — inaczej literówka w JSON-ie po stronie klienta wygląda w
 * monitoringu identycznie jak realna awaria serwera i topi ją w szumie.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ErrorHandlingIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

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
}
