package com.songly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class ConcurrentDecisionsIntegrationTest extends AbstractIntegrationTest {

	private static final int PARALLEL = 3;
	private static final String PASSWORD = "password123";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private record TestUser(String token, String refreshToken, UUID id) {
	}

	@Test
	void parallelDismissals_recordOneDecision() throws Exception {
		TestUser owner = user("dismiss-owner");
		TestUser reporter = user("dismiss-reporter");
		TestUser author = user("dismiss-author");
		String communityId = community(owner, "Odrzucenia " + UUID.randomUUID());
		join(reporter, communityId);
		join(author, communityId);
		String postId = json(mockMvc.perform(post("/api/v1/communities/" + communityId + "/posts")
				.header("Authorization", bearer(author)).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"post do zgłoszenia"}""")).andExpect(status().isCreated()).andReturn()).get("id")
				.asText();
		mockMvc.perform(post("/api/v1/posts/" + postId + "/reports").header("Authorization", bearer(reporter))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"SPAM"}""")).andExpect(status().isAccepted());
		String reportId = json(mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization", bearer(owner)))
				.andExpect(status().isOk()).andReturn()).get("content").get(0).get("id").asText();

		List<Integer> statuses = parallel(() -> mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
						.header("Authorization", bearer(owner)))
				.andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnly(204);
		assertThat(count("select count(*) from reporter_decisions where user_id = ? and outcome = 'DISMISSED'",
				reporter.id())).isEqualTo(1);
		assertThat(notifications(reporter, "POST_REPORT_DISMISSED")).isEqualTo(1);
	}

	@Test
	void parallelBanAppeals_acceptOne() throws Exception {
		TestUser owner = user("appeal-owner");
		TestUser banned = user("appeal-banned");
		String communityId = community(owner, "Odwołania " + UUID.randomUUID());
		join(banned, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + banned.id())
				.header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isNoContent());

		List<Integer> statuses = parallel(
				() -> mockMvc.perform(post("/api/v1/communities/" + communityId + "/ban-appeal")
						.header("Authorization", bearer(banned)).contentType(MediaType.APPLICATION_JSON).content("""
								{"text":"to pomyłka"}""")).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnlyOnce(204).containsOnly(204, 409);
		assertThat(notifications(owner, "BAN_APPEAL_SUBMITTED")).isEqualTo(1);
	}

	@Test
	void parallelSuspensionAppeals_acceptOne() throws Exception {
		TestUser admin = user("suspension-admin");
		jdbcTemplate.update("UPDATE users SET is_admin = TRUE WHERE id = ?", admin.id());
		TestUser suspended = user("suspension-target");
		mockMvc.perform(put("/api/v1/admin/users/" + suspended.id() + "/suspension")
				.header("Authorization", bearer(admin)).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"nękanie"}""")).andExpect(status().isNoContent());

		List<Integer> statuses = parallel(() -> mockMvc.perform(post("/api/v1/auth/me/suspension/appeal")
				.header("Authorization", bearer(suspended)).contentType(MediaType.APPLICATION_JSON).content("""
						{"text":"to pomyłka"}""")).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnlyOnce(204).containsOnly(204, 409);
	}

	@Test
	void parallelRefreshes_leaveAtMostOneChain() throws Exception {
		TestUser owner = user("refresh-owner");

		List<MvcResult> results = parallelResults(
				() -> mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
						{"refreshToken":"%s"}""".formatted(owner.refreshToken()))).andReturn());

		int liveChains = 0;
		for (MvcResult result : results) {
			if (result.getResponse().getStatus() == 200) {
				String next = json(result).get("refreshToken").asText();
				if (mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("""
						{"refreshToken":"%s"}""".formatted(next))).andReturn().getResponse().getStatus() == 200) {
					liveChains++;
				}
			}
		}
		assertThat(liveChains).isLessThanOrEqualTo(1);
	}

	@Test
	void crossingFriendRequests_doNotLeaveTwoPending() throws Exception {
		TestUser first = user("crossing-first");
		TestUser second = user("crossing-second");

		parallel(List.of(() -> invite(first, second), () -> invite(second, first)));

		assertThat(count("""
				select count(*) from friend_requests where status = 'PENDING'
				and ((requester_id = ? and addressee_id = ?) or (requester_id = ? and addressee_id = ?))""", first.id(),
				second.id(), second.id(), first.id())).isLessThanOrEqualTo(1);
	}

	@Test
	void communityNamesDifferingInCase_cannotBothBeCreated() throws Exception {
		TestUser first = user("names-first");
		TestUser second = user("names-second");
		String name = "Nazwa " + UUID.randomUUID();

		List<MvcResult> results = parallel(
				List.of(() -> createCommunity(first, name), () -> createCommunity(second, name.toUpperCase())));

		List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).toList();
		assertThat(statuses).containsOnlyOnce(201).containsOnly(201, 409);
		MvcResult loser = results.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst()
				.orElseThrow();
		assertThat(json(loser).get("code").asText()).isEqualTo("COMMUNITY_NAME_TAKEN");
		assertThat(count("select count(*) from communities where lower(name) = lower(?)", name)).isEqualTo(1);
	}

	private int invite(TestUser from, TestUser to) throws Exception {
		return mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", bearer(from))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(to.id()))).andReturn().getResponse().getStatus();
	}

	private MvcResult createCommunity(TestUser owner, String name) throws Exception {
		return mockMvc.perform(post("/api/v1/communities").header("Authorization", bearer(owner))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"name":"%s","description":"opis"}""".formatted(name))).andReturn();
	}

	private List<Integer> parallel(Callable<Integer> request) throws Exception {
		List<Callable<Integer>> requests = new ArrayList<>();
		for (int i = 0; i < PARALLEL; i++) {
			requests.add(request);
		}
		return parallel(requests);
	}

	private List<MvcResult> parallelResults(Callable<MvcResult> request) throws Exception {
		List<Callable<MvcResult>> requests = new ArrayList<>();
		for (int i = 0; i < PARALLEL; i++) {
			requests.add(request);
		}
		return parallel(requests);
	}

	private <T> List<T> parallel(List<Callable<T>> requests) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(requests.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> request : requests) {
				futures.add(pool.submit(() -> {
					start.await();
					return request.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

	private TestUser user(String prefix) throws Exception {
		JsonNode tokens = json(registerAndVerify(prefix + "-" + UUID.randomUUID() + "@race.test", PASSWORD));
		String token = tokens.get("accessToken").asText();
		UUID id = UUID
				.fromString(json(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn()).get("userId").asText());
		return new TestUser(token, tokens.get("refreshToken").asText(), id);
	}

	private String community(TestUser owner, String name) throws Exception {
		return json(mockMvc
				.perform(post("/api/v1/communities").header("Authorization", bearer(owner))
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"name":"%s","description":"opis"}""".formatted(name)))
				.andExpect(status().isCreated()).andReturn()).get("id").asText();
	}

	private void join(TestUser user, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization", bearer(user)))
				.andExpect(status().isNoContent());
	}

	private long notifications(TestUser recipient, String type) {
		return count("select count(*) from notifications where user_id = ? and type = ?", recipient.id(), type);
	}

	private long count(String sql, Object... args) {
		return jdbcTemplate.queryForObject(sql, Long.class, args);
	}

	private JsonNode json(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static String bearer(TestUser user) {
		return "Bearer " + user.token();
	}
}
