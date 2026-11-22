package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class NowPlayingStreamAccountDeletionIntegrationTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NowPlayingStreamHub streamHub;

	@Test
	void accountDeletion_closesOpenStreams() throws Exception {
		String token = objectMapper
				.readTree(registerAndVerify("streaming-" + UUID.randomUUID() + "@deleted.test", PASSWORD).getResponse()
						.getContentAsString())
				.get("accessToken").asText();
		UUID userId = UUID.fromString(objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText());
		SseEmitter openStream = mock(SseEmitter.class);
		streamHub.register(userId, openStream);

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"%s"}""".formatted(PASSWORD))).andExpect(status().isNoContent());

		verify(openStream).complete();
		assertThat(streamHub.hasSubscribers(userId)).isFalse();
	}
}
