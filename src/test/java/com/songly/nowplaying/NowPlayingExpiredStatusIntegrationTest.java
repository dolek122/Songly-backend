package com.songly.nowplaying;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.now-playing.poll.interval=PT1H",
		"songly.now-playing.stream.heartbeat.enabled=false"})
class NowPlayingExpiredStatusIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private NowPlayingStreamHub streamHub;

	@Autowired
	private NowPlayingPoller poller;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private record TestUser(String accessToken, String userId) {
	}

	private TestUser registerUser(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		String accessToken = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult profile = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		return new TestUser(accessToken,
				objectMapper.readTree(profile.getResponse().getContentAsString()).get("userId").asText());
	}

	private void befriend(TestUser a, TestUser b) throws Exception {
		MvcResult request = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + a.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(b.userId())))
				.andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(request.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + b.accessToken())).andExpect(status().isOk());
	}

	private BlockingQueue<Object> watchStreamOf(TestUser viewer) {
		BlockingQueue<Object> pushes = new LinkedBlockingQueue<>();
		streamHub.register(UUID.fromString(viewer.userId()), new SseEmitter() {
			@Override
			public void send(SseEventBuilder builder) {
				pushes.add(builder);
			}
		});
		return pushes;
	}

	private void setManualStatus(TestUser user, String title) throws Exception {
		mockMvc.perform(put("/api/v1/now-playing/manual").header("Authorization", "Bearer " + user.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"title":"%s","artist":"Queen","source":"Tidal"}""".formatted(title)))
				.andExpect(status().isNoContent());
	}

	@Test
	void expiredManualStatus_pushesStoppedToWatchingFriend() throws Exception {
		TestUser viewer = registerUser("np-expiry-viewer@songly.io");
		TestUser subject = registerUser("np-expiry-subject@songly.io");
		befriend(viewer, subject);
		BlockingQueue<Object> pushes = watchStreamOf(viewer);

		setManualStatus(subject, "Bohemian Rhapsody");
		assertThat(pushes.poll(10, TimeUnit.SECONDS)).as("push za ustawienie statusu").isNotNull();

		redisTemplate.delete("now-playing:manual:" + subject.userId());
		poller.pollObservedUsers();

		assertThat(pushes.poll(10, TimeUnit.SECONDS)).as("push \"przestał słuchać\" po wygaśnięciu statusu")
				.isNotNull();
	}

	@Test
	void pollStaysQuiet_whenStatusIsStillAlive() throws Exception {
		TestUser viewer = registerUser("np-expiry-quiet-viewer@songly.io");
		TestUser subject = registerUser("np-expiry-quiet-subject@songly.io");
		befriend(viewer, subject);
		BlockingQueue<Object> pushes = watchStreamOf(viewer);

		setManualStatus(subject, "Come As You Are");
		assertThat(pushes.poll(10, TimeUnit.SECONDS)).as("push za ustawienie statusu").isNotNull();

		poller.pollObservedUsers();

		assertThat(pushes.poll(2, TimeUnit.SECONDS)).as("żywy status nie generuje kolejnego pushu").isNull();
	}
}
