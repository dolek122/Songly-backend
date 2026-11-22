package com.songly.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class HealthCheckIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void readiness_isPublicAndUp() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readiness_checksDatabaseButNotRedisOrMail() throws Exception {
		String token = objectMapper
				.readTree(registerAndVerify("health-" + UUID.randomUUID() + "@health.test", "password123").getResponse()
						.getContentAsString())
				.get("accessToken").asText();

		JsonNode readiness = objectMapper
				.readTree(mockMvc.perform(get("/actuator/health/readiness").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		List<String> components = new ArrayList<>();
		readiness.path("components").propertyNames().forEach(components::add);
		assertThat(components).containsExactlyInAnyOrder("db", "readinessState");
	}
}
