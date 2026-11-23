package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.RecordingMailSenderConfig.SentMail;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PlatformPostReportIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private record TestUser(String token, String userId, String email) {
	}

	private TestUser user(String prefix) throws Exception {
		String email = prefix + "-" + UUID.randomUUID() + "@platform-report.test";
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = json(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("userId").asText();
		return new TestUser(token, userId, email);
	}

	private TestUser admin(String prefix) throws Exception {
		TestUser user = user(prefix);
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", UUID.fromString(user.userId()));
		return user;
	}

	private JsonNode json(String body) {
		return objectMapper.readTree(body);
	}

	private String createPost(TestUser author, String content) throws Exception {
		return json(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private String createCommunityPost(TestUser owner, String content) throws Exception {
		String communityId = json(mockMvc
				.perform(post("/api/v1/communities").header("Authorization", "Bearer " + owner.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"name":"%s","description":"opis"}""".formatted("Plat " + UUID.randomUUID())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		return json(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private int report(TestUser reporter, String postId) throws Exception {
		return mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM","details":"reklama"}"""))
				.andReturn().getResponse().getStatus();
	}

	private List<JsonNode> queueFor(TestUser adminUser, String postId) throws Exception {
		JsonNode content = json(mockMvc
				.perform(get("/api/v1/admin/post-reports?size=100").header("Authorization",
						"Bearer " + adminUser.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
		List<JsonNode> entries = new ArrayList<>();
		for (JsonNode entry : content) {
			if (postId.equals(entry.get("post").get("id").asText())) {
				entries.add(entry);
			}
		}
		return entries;
	}

	private int dismiss(TestUser adminUser, String reportId) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/post-reports/" + reportId + "/dismissal").header("Authorization",
				"Bearer " + adminUser.token())).andReturn().getResponse().getStatus();
	}

	private int remove(TestUser adminUser, String postId) throws Exception {
		return mockMvc
				.perform(delete("/api/v1/admin/posts/" + postId).header("Authorization", "Bearer " + adminUser.token()))
				.andReturn().getResponse().getStatus();
	}

	private List<JsonNode> notificationsOfType(TestUser user, String type) throws Exception {
		JsonNode content = json(
				mockMvc.perform(get("/api/v1/notifications").header("Authorization", "Bearer " + user.token()))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("content");
		List<JsonNode> matching = new ArrayList<>();
		for (JsonNode node : content) {
			if (type.equals(node.get("type").asText())) {
				matching.add(node);
			}
		}
		return matching;
	}

	private List<JsonNode> platformLog(TestUser adminUser, TestUser subject) throws Exception {
		JsonNode content = json(mockMvc
				.perform(get("/api/v1/admin/moderation-log?userId=" + subject.userId()).header("Authorization",
						"Bearer " + adminUser.token()))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("content");
		List<JsonNode> entries = new ArrayList<>();
		content.forEach(entries::add);
		return entries;
	}

	@Test
	void regularPostCanBeReported_andAdminsAreNotifiedOncePerPendingPost() throws Exception {
		TestUser adminUser = admin("notified-admin");
		TestUser author = user("notified-author");
		TestUser first = user("notified-first");
		TestUser second = user("notified-second");
		String postId = createPost(author, "zwykly post");
		redisTemplate.delete("moderation-mail:report:platform");
		mails.clearFor(adminUser.email());

		assertThat(report(author, postId)).isEqualTo(400);
		assertThat(report(first, postId)).isEqualTo(202);
		assertThat(report(first, postId)).isEqualTo(409);
		assertThat(report(second, postId)).isEqualTo(202);

		List<JsonNode> notifications = notificationsOfType(adminUser, "POST_REPORTED");
		assertThat(notifications).hasSize(1);
		assertThat(notifications.get(0).get("resourceId").asText()).isEqualTo(postId);
		assertThat(notificationsOfType(author, "POST_REPORTED")).isEmpty();
		assertThat(mails.sentTo(adminUser.email())).extracting(SentMail::subject)
				.containsExactly("Songly — nowe zgłoszenie posta");
	}

	@Test
	void adminQueueShowsOnlyReportsOnRegularPosts() throws Exception {
		TestUser adminUser = admin("queue-admin");
		TestUser author = user("queue-author");
		TestUser reporter = user("queue-reporter");
		String regular = createPost(author, "post na tablicy");
		String inCommunity = createCommunityPost(author, "post w spolecznosci");
		report(reporter, regular);
		report(reporter, inCommunity);

		List<JsonNode> entries = queueFor(adminUser, regular);
		assertThat(entries).hasSize(1);
		JsonNode entry = entries.get(0);
		assertThat(entry.get("reason").asText()).isEqualTo("SPAM");
		assertThat(entry.get("details").asText()).isEqualTo("reklama");
		assertThat(entry.get("reporterId").asText()).isEqualTo(reporter.userId());
		assertThat(entry.get("reportCount").asLong()).isEqualTo(1);
		assertThat(entry.get("post").get("content").asText()).isEqualTo("post na tablicy");
		assertThat(queueFor(adminUser, inCommunity)).isEmpty();

		mockMvc.perform(get("/api/v1/admin/post-reports").header("Authorization", "Bearer " + reporter.token()))
				.andExpect(status().isForbidden());
	}

	@Test
	void dismissingAReport_notifiesTheReporterAndCountsAgainstThemInTheQueue() throws Exception {
		TestUser adminUser = admin("dismiss-admin");
		TestUser author = user("dismiss-author");
		TestUser reporter = user("dismiss-reporter");
		String fine = createPost(author, "post w porzadku");
		String next = createPost(author, "kolejny post");
		report(reporter, fine);
		String reportId = queueFor(adminUser, fine).get(0).get("id").asText();

		assertThat(dismiss(reporter, reportId)).isEqualTo(403);
		assertThat(dismiss(adminUser, reportId)).isEqualTo(204);
		assertThat(dismiss(adminUser, reportId)).isEqualTo(204);

		assertThat(queueFor(adminUser, fine)).isEmpty();
		assertThat(notificationsOfType(reporter, "POST_REPORT_DISMISSED")).hasSize(1);
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from reporter_decisions where user_id = ? and outcome = 'DISMISSED' and community_id is null",
				Long.class, UUID.fromString(reporter.userId()))).isEqualTo(1);
		assertThat(platformLog(adminUser, reporter)).singleElement().satisfies(entry -> {
			assertThat(entry.get("action").asText()).isEqualTo("POST_REPORT_DISMISSED");
			assertThat(entry.get("communityId").isNull()).isTrue();
			assertThat(entry.get("actorId").asText()).isEqualTo(adminUser.userId());
		});
		assertThat(mails.lastTo(reporter.email()).body()).contains("Administracja Songly");

		report(reporter, next);
		JsonNode entry = queueFor(adminUser, next).get(0);
		assertThat(entry.get("reporterDismissedCount").asInt()).isEqualTo(1);
		assertThat(entry.get("reporterUpheldCount").asInt()).isZero();
	}

	@Test
	void communityReportsCannotBeDismissedFromTheAdminQueue() throws Exception {
		TestUser adminUser = admin("scope-admin");
		TestUser owner = user("scope-owner");
		TestUser reporter = user("scope-reporter");
		String postId = createCommunityPost(owner, "post moderowany przez spolecznosc");
		report(reporter, postId);
		String reportId = jdbcTemplate.queryForObject("select id from post_reports where post_id = ?", String.class,
				UUID.fromString(postId));

		assertThat(dismiss(adminUser, reportId)).isEqualTo(404);
		assertThat(dismiss(adminUser, UUID.randomUUID().toString())).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("select status from post_reports where id = ?", String.class,
				UUID.fromString(reportId))).isEqualTo("PENDING");
	}

	@Test
	void removingAPost_notifiesTheAuthorAnonymouslyAndUpholdsEveryReport() throws Exception {
		TestUser adminUser = admin("remove-admin");
		TestUser author = user("remove-author");
		TestUser first = user("remove-first");
		TestUser second = user("remove-second");
		String spam = createPost(author, "spam");
		String later = createPost(author, "pozniejszy post");
		report(first, spam);
		report(second, spam);

		assertThat(remove(first, spam)).isEqualTo(403);
		assertThat(remove(adminUser, spam)).isEqualTo(204);
		assertThat(remove(adminUser, spam)).isEqualTo(404);

		mockMvc.perform(get("/api/v1/posts/" + spam).header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isNotFound());
		List<JsonNode> removed = notificationsOfType(author, "POST_REMOVED");
		assertThat(removed).hasSize(1);
		assertThat(removed.get(0).get("resourceId").asText()).isEqualTo(spam);
		assertThat(removed.get(0).get("actorDisplayName").isNull()).isTrue();
		assertThat(mails.lastTo(author.email()).subject()).isEqualTo("Songly — Twój post został usunięty");
		assertThat(platformLog(adminUser, author)).singleElement().satisfies(entry -> {
			assertThat(entry.get("action").asText()).isEqualTo("POST_REMOVED");
			assertThat(entry.get("communityId").isNull()).isTrue();
			assertThat(entry.get("reportCount").asInt()).isEqualTo(2);
		});
		assertThat(platformLog(adminUser, first)).extracting(entry -> entry.get("action").asText())
				.containsExactly("POST_REPORT_UPHELD");

		report(first, later);
		assertThat(queueFor(adminUser, later).get(0).get("reporterUpheldCount").asInt()).isEqualTo(1);
	}

	@Test
	void communityPostsAreLeftToTheCommunityModerators() throws Exception {
		TestUser adminUser = admin("community-admin");
		TestUser owner = user("community-owner");
		String postId = createCommunityPost(owner, "post spolecznosci");

		mockMvc.perform(delete("/api/v1/admin/posts/" + postId).header("Authorization", "Bearer " + adminUser.token()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("POST_IN_COMMUNITY"));
		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isOk());
	}

	@Test
	void parallelRemovals_notifyTheAuthorOnce() throws Exception {
		TestUser firstAdmin = admin("race-admin1");
		TestUser secondAdmin = admin("race-admin2");
		TestUser author = user("race-author");
		TestUser reporter = user("race-reporter");
		String postId = createPost(author, "post do usuniecia");
		report(reporter, postId);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<Integer> statuses = new ArrayList<>();
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (TestUser adminUser : List.of(firstAdmin, secondAdmin)) {
				futures.add(pool.submit(() -> {
					start.await();
					return remove(adminUser, postId);
				}));
			}
			start.countDown();
			for (Future<Integer> future : futures) {
				statuses.add(future.get());
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(statuses).containsExactlyInAnyOrder(204, 404);
		assertThat(notificationsOfType(author, "POST_REMOVED")).hasSize(1);
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from reporter_decisions where user_id = ? and outcome = 'UPHELD'", Long.class,
				UUID.fromString(reporter.userId()))).isEqualTo(1);
	}
}
