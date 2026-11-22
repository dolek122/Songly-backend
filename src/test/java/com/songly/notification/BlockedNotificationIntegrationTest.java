package com.songly.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class BlockedNotificationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String token, String userId) {
	}

	private TestUser register(String email) throws Exception {
		MvcResult result = registerAndVerify(email, "password123");
		String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
		MvcResult profile = mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn();
		return new TestUser(token,
				objectMapper.readTree(profile.getResponse().getContentAsString()).get("userId").asText());
	}

	private long unreadCount(TestUser user) throws Exception {
		MvcResult result = mockMvc
				.perform(get("/api/v1/notifications/unread-count").header("Authorization", "Bearer " + user.token()))
				.andExpect(status().isOk()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("count").asLong();
	}

	private String likeAndBlock(TestUser author, TestUser liker) throws Exception {
		MvcResult postResult = mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"post autora"}""")).andExpect(status().isCreated()).andReturn();
		String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + liker.token()))
				.andExpect(status().isNoContent());
		return postId;
	}

	@Test
	void notificationsFromBlockedUser_areHiddenAndRestoredOnUnblock() throws Exception {
		TestUser author = register("notif-block-author@songly.io");
		TestUser troll = register("notif-block-troll@songly.io");
		likeAndBlock(author, troll);

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].actorId").value(troll.userId()));

		mockMvc.perform(put("/api/v1/blocks/" + troll.userId()).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
		org.assertj.core.api.Assertions.assertThat(unreadCount(author)).isZero();

		mockMvc.perform(delete("/api/v1/blocks/" + troll.userId()).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].actorId").value(troll.userId()));
		org.assertj.core.api.Assertions.assertThat(unreadCount(author)).isEqualTo(1);
	}

	@Test
	void markAllRead_doesNotConsumeHiddenNotifications() throws Exception {
		TestUser author = register("notif-block-markall@songly.io");
		TestUser troll = register("notif-block-markall-troll@songly.io");
		likeAndBlock(author, troll);

		mockMvc.perform(put("/api/v1/blocks/" + troll.userId()).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/notifications/read-all").header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete("/api/v1/blocks/" + troll.userId()).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNoContent());

		org.assertj.core.api.Assertions.assertThat(unreadCount(author)).isEqualTo(1);
		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content[0].readAt").doesNotExist());
	}

	@Test
	void blockIsSymmetric_inTheNotificationInboxToo() throws Exception {
		TestUser blocker = register("notif-block-sym-blocker@songly.io");
		TestUser other = register("notif-block-sym-other@songly.io");
		likeAndBlock(other, blocker);

		mockMvc.perform(put("/api/v1/blocks/" + other.userId()).header("Authorization", "Bearer " + blocker.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + other.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.page.totalElements").value(0));
	}
}
