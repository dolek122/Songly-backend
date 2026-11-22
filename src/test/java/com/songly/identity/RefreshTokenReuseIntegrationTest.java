package com.songly.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class RefreshTokenReuseIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private SessionRepository sessionRepository;

	private String register(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		return refreshTokenOf(result);
	}

	private String refreshTokenOf(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("refreshToken").asText();
	}

	private MvcResult refresh(String refreshToken, int expectedStatus) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(refreshToken))).andExpect(status().is(expectedStatus)).andReturn();
	}

	@Test
	void replayingAConsumedToken_killsTheSessionItWasRotatedInto() throws Exception {
		String stolen = register("reuse-core@songly.io");
		String rotated = refreshTokenOf(refresh(stolen, 200));

		refresh(stolen, 401);

		refresh(rotated, 401);
	}

	@Test
	void reuseIsStillDetected_afterSessionCleanupRan() throws Exception {
		String stolen = register("reuse-after-cleanup@songly.io");
		String rotated = refreshTokenOf(refresh(stolen, 200));
		Instant now = Instant.now();
		jdbcTemplate.update("UPDATE sessions SET revoked_at = ? WHERE refresh_token_hash = ?",
				now.minus(8, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), SecureTokens.hash(stolen));

		sessionRepository.deleteDeadSessions(now.minus(7, ChronoUnit.DAYS), 5_000);

		refresh(stolen, 401);
		refresh(rotated, 401);
	}

	@Test
	void reuseResponse_looksExactlyLikeAnyOtherBadToken() throws Exception {
		String stolen = register("reuse-opaque@songly.io");
		refresh(stolen, 200);

		MvcResult reuse = refresh(stolen, 401);
		MvcResult garbage = refresh("zupelnie-nieistniejacy-token", 401);

		var reuseBody = objectMapper.readTree(reuse.getResponse().getContentAsString());
		var garbageBody = objectMapper.readTree(garbage.getResponse().getContentAsString());
		Assertions.assertThat(reuseBody.get("code").asText()).isEqualTo(garbageBody.get("code").asText());
		Assertions.assertThat(reuseBody.get("message").asText()).isEqualTo(garbageBody.get("message").asText());
	}

	@Test
	void reuseRevokesSessionsFromEveryDevice() throws Exception {
		String phone = register("reuse-devices@songly.io");
		MvcResult loginResult = mockMvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"reuse-devices@songly.io","password":"password123"}""")).andExpect(status().isOk())
				.andReturn();
		String laptop = refreshTokenOf(loginResult);

		refresh(phone, 200);
		refresh(phone, 401);

		refresh(laptop, 401);
	}

	@Test
	void afterReuse_userRecoversByLoggingInAgain() throws Exception {
		String stolen = register("reuse-recovery@songly.io");
		refresh(stolen, 200);
		refresh(stolen, 401);

		MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"reuse-recovery@songly.io","password":"password123"}""")).andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").isNotEmpty()).andReturn();

		refresh(refreshTokenOf(login), 200);
	}

	@Test
	void loggingOutTwice_doesNotTakeDownOtherDevices() throws Exception {
		String phone = register("reuse-logout@songly.io");
		MvcResult loginResult = mockMvc
				.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"reuse-logout@songly.io","password":"password123"}""")).andExpect(status().isOk())
				.andReturn();
		String laptop = refreshTokenOf(loginResult);

		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(phone))).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("""
				{"refreshToken":"%s"}""".formatted(phone))).andExpect(status().isNoContent());

		refresh(laptop, 200);
	}
}
