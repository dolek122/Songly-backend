package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = {"songly.security.rate-limit.enabled=false", "songly.content.report.limit=2"})
class ReportAbuseIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private MeterRegistry meterRegistry;

	private record TestUser(String token, String userId, String email) {
	}

	private double rejectionCount(String reason) {
		return meterRegistry.counter("songly.moderation.report.rejected", "reason", reason).count();
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId, email);
	}

	private String createCommunity(TestUser owner, String name) throws Exception {
		return objectMapper
				.readTree(mockMvc
						.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
								.contentType(MediaType.APPLICATION_JSON).content("""
										{"name":"%s","description":"opis"}""".formatted(name)))
						.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
				.get("id").asText();
	}

	private void join(TestUser user, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + user.token())).andExpect(status().isNoContent());
	}

	private String createPost(TestUser author, String communityId, String content) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + author.token()).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private org.springframework.test.web.servlet.ResultActions report(TestUser reporter, String postId)
			throws Exception {
		return mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""));
	}

	private long countOfType(TestUser user, String type) throws Exception {
		JsonNode content = objectMapper
				.readTree(
						mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + user.token()))
								.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
		long count = 0;
		for (JsonNode node : content) {
			if (type.equals(node.get("type").asText())) {
				count++;
			}
		}
		return count;
	}

	@Test
	void bannedUserCannotReport() throws Exception {
		TestUser owner = user("reportabuse-ban-owner@songly.io");
		TestUser author = user("reportabuse-ban-author@songly.io");
		TestUser troll = user("reportabuse-ban-troll@songly.io");
		String communityId = createCommunity(owner, "Reportabuse-ban");
		join(author, communityId);
		join(troll, communityId);
		String postId = createPost(author, communityId, "zwykly post");
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + troll.userId())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());

		report(troll, postId).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("BANNED_FROM_COMMUNITY"));
	}

	@Test
	void oneReporterIsCappedAcrossDifferentPosts() throws Exception {
		TestUser owner = user("reportabuse-cap-owner@songly.io");
		TestUser author = user("reportabuse-cap-author@songly.io");
		TestUser reporter = user("reportabuse-cap-reporter@songly.io");
		String communityId = createCommunity(owner, "Reportabuse-limit");
		join(author, communityId);
		join(reporter, communityId);
		String first = createPost(author, communityId, "post pierwszy");
		String second = createPost(author, communityId, "post drugi");
		String third = createPost(author, communityId, "post trzeci");

		report(reporter, first).andExpect(status().isAccepted());
		report(reporter, second).andExpect(status().isAccepted());

		double rejectionsBefore = rejectionCount("rate-limit");
		report(reporter, third).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("REPORT_RATE_LIMIT_EXCEEDED"));
		assertThat(rejectionCount("rate-limit")).isEqualTo(rejectionsBefore + 1);

		TestUser someoneElse = user("reportabuse-cap-other@songly.io");
		join(someoneElse, communityId);
		report(someoneElse, third).andExpect(status().isAccepted());
	}

	@Test
	void moderatorMailIsThrottledWhileQueueAndInboxAreNot() throws Exception {
		TestUser owner = user("reportabuse-mail-owner@songly.io");
		TestUser author = user("reportabuse-mail-author@songly.io");
		TestUser firstReporter = user("reportabuse-mail-rep1@songly.io");
		TestUser secondReporter = user("reportabuse-mail-rep2@songly.io");
		String communityId = createCommunity(owner, "Reportabuse-poczta");
		join(author, communityId);
		join(firstReporter, communityId);
		join(secondReporter, communityId);
		String first = createPost(author, communityId, "post pierwszy");
		String second = createPost(author, communityId, "post drugi");
		mails.clearFor(owner.email());

		report(firstReporter, first).andExpect(status().isAccepted());
		report(secondReporter, second).andExpect(status().isAccepted());

		assertThat(mails.sentTo(owner.email())).hasSize(1);
		assertThat(countOfType(owner, "COMMUNITY_POST_REPORTED")).isEqualTo(2);
		JsonNode queue = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(queue.get("page").get("totalElements").asInt()).isEqualTo(2);
	}
}
