package com.songly.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

	@Test
	void missingRequiredRequestParameter_returnsBadRequest() throws Exception {
		mockMvc.perform(get("/api/v1/now-playing/spotify/callback")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
	}

	@Test
	void unsupportedContentType_returnsUnsupportedMediaType() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.TEXT_PLAIN).content("cokolwiek"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void multipartWithoutRequiredPart_returnsBadRequest() throws Exception {
		MvcResult registration = registerAndVerify("missing-part@songly.io", "password123");
		String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString()).get("accessToken")
				.asText();

		mockMvc.perform(multipart("/api/v1/profiles/me/avatar").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MISSING_PARAMETER"));
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

	@Test
	void unknownRouteWhenAuthenticated_returnsNotFound() throws Exception {
		MvcResult registration = registerAndVerify("unknown-route@songly.io", "password123");
		String accessToken = objectMapper.readTree(registration.getResponse().getContentAsString()).get("accessToken")
				.asText();

		mockMvc.perform(get("/api/v1/nie-ma-takiej-sciezki").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
				.andExpect(jsonPath("$.path").value("/api/v1/nie-ma-takiej-sciezki"));
	}

	@Test
	void unknownRouteWhenAnonymous_returnsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/v1/nie-ma-takiej-sciezki")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
				.andExpect(header().string("Content-Type", "application/json;charset=UTF-8"));
	}
}
