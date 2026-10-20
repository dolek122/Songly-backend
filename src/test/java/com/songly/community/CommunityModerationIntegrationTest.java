package com.songly.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Moderacja społeczności (ADR 0011): moderatorzy, bany i kolejka zgłoszeń.
 *
 * <p>
 * Pętla, którą ten test zamyka: dało się już usunąć post w swojej społeczności,
 * ale nie dało się <b>zgłosić</b> niczego ani powstrzymać kogoś przed
 * natychmiastowym powrotem. Moderacja sprowadzała się do wyścigu na kliknięcia
 * jednej osoby.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class CommunityModerationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String token, String userId) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + token))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();
		return new TestUser(token, userId);
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

	private void join(TestUser user, String communityId, int expectedStatus) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + user.token())).andExpect(status().is(expectedStatus));
	}

	private String createPost(TestUser author, String communityId, String content) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + author.token()).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
	}

	private void setRole(TestUser requester, String communityId, String userId, String role, int expectedStatus)
			throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + userId + "/role")
				.header("Authorization", "Bearer " + requester.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"role":"%s"}""".formatted(role))).andExpect(status().is(expectedStatus));
	}

	private void ban(TestUser requester, String communityId, String userId, int expectedStatus) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + userId)
				.header("Authorization", "Bearer " + requester.token()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"spam"}""")).andExpect(status().is(expectedStatus));
	}

	private void report(TestUser reporter, String postId, int expectedStatus) throws Exception {
		mockMvc.perform(
				post("/api/v1/posts/" + postId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM","details":"reklama"}"""))
				.andExpect(status().is(expectedStatus));
	}

	/**
	 * Ban na konto, którego nie ma (usunięte chwilę wcześniej, zły identyfikator),
	 * trafia w klucz obcy do {@code users}. To konflikt ze stanem danych, a nie
	 * awaria serwera — wcześniej wracało jako 500 ze stack tracem.
	 */
	@Test
	void banningNonexistentUser_isAConflictNotAServerError() throws Exception {
		TestUser owner = user("moderation-ghost-owner@songly.io");
		String communityId = createCommunity(owner, "Moderacja-duch");

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + UUID.randomUUID())
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"reason":"spam"}""")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONFLICT"));
	}

	/**
	 * Moderator dostaje prawo kasowania cudzych postów — dotąd miał je wyłącznie
	 * właściciel, więc moderacja była wąskim gardłem jednej osoby.
	 */
	@Test
	void moderatorCanDeleteOthersPosts_ordinaryMemberCannot() throws Exception {
		TestUser owner = user("mod-owner@songly.io");
		TestUser moderator = user("mod-moderator@songly.io");
		TestUser member = user("mod-member@songly.io");
		String communityId = createCommunity(owner, "Moderowana");
		join(moderator, communityId, 204);
		join(member, communityId, 204);
		setRole(owner, communityId, moderator.userId(), "MODERATOR", 204);
		String postId = createPost(member, communityId, "post czlonka");

		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + member.token()))
				.andExpect(status().isNoContent());
		String secondPostId = createPost(member, communityId, "drugi post");
		mockMvc.perform(delete("/api/v1/posts/" + secondPostId).header("Authorization", "Bearer " + moderator.token()))
				.andExpect(status().isNoContent());

		// Zwykły członek dalej nie moderuje niczego poza własnymi postami.
		String ownerPostId = createPost(owner, communityId, "post wlasciciela");
		mockMvc.perform(delete("/api/v1/posts/" + ownerPostId).header("Authorization", "Bearer " + member.token()))
				.andExpect(status().isForbidden());
	}

	/**
	 * Role rozdaje wyłącznie właściciel — inaczej moderator przejąłby społeczność.
	 */
	@Test
	void onlyOwnerAssignsRoles() throws Exception {
		TestUser owner = user("role-owner@songly.io");
		TestUser moderator = user("role-moderator@songly.io");
		TestUser member = user("role-member@songly.io");
		String communityId = createCommunity(owner, "Role");
		join(moderator, communityId, 204);
		join(member, communityId, 204);
		setRole(owner, communityId, moderator.userId(), "MODERATOR", 204);

		setRole(moderator, communityId, member.userId(), "MODERATOR", 403);
		// Właściciela nie da się zdegradować nawet własną ręką — to zostawiłoby
		// społeczność bez właściciela.
		setRole(owner, communityId, owner.userId(), "MEMBER", 403);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + member.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"OWNER"}""")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CANNOT_ASSIGN_OWNER_ROLE"));
	}

	/**
	 * Sedno banu: samo usunięcie z listy członków nic by nie dało, bo dołączenie
	 * jest otwarte i natychmiastowe (§8). Dopiero blokada powrotu czyni decyzję
	 * trwałą.
	 */
	@Test
	void bannedUserLosesMembershipAndCannotRejoinOrPost() throws Exception {
		TestUser owner = user("ban-owner@songly.io");
		TestUser troll = user("ban-troll@songly.io");
		String communityId = createCommunity(owner, "Zbanowani");
		join(troll, communityId, 204);

		ban(owner, communityId, troll.userId(), 204);

		join(troll, communityId, 403);
		mockMvc.perform(post("/api/v1/communities/" + communityId + "/posts")
				.header("Authorization", "Bearer " + troll.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"wracam"}""")).andExpect(status().isForbidden());
	}

	/**
	 * Ban odcina od <b>pisania</b>, nie od czytania. Społeczności są publiczne,
	 * więc ukrywanie treści byłoby ochroną pozorną — wystarczy się wylogować.
	 */
	@Test
	void bannedUserStillReadsButCannotComment() throws Exception {
		TestUser owner = user("ban-read-owner@songly.io");
		TestUser troll = user("ban-read-troll@songly.io");
		String communityId = createCommunity(owner, "Czytelnia");
		join(troll, communityId, 204);
		String postId = createPost(owner, communityId, "tresc spolecznosci");
		ban(owner, communityId, troll.userId(), 204);

		mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/posts").header("Authorization", "Bearer " + troll.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));

		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + troll.token())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"dalej zaczepiam"}""")).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("BANNED_FROM_COMMUNITY"));
	}

	@Test
	void ownerCannotBeBanned_andModeratorCannotBanModerator() throws Exception {
		TestUser owner = user("ban-owner2@songly.io");
		TestUser modA = user("ban-moda@songly.io");
		TestUser modB = user("ban-modb@songly.io");
		String communityId = createCommunity(owner, "Bez-samobojstw");
		join(modA, communityId, 204);
		join(modB, communityId, 204);
		setRole(owner, communityId, modA.userId(), "MODERATOR", 204);
		setRole(owner, communityId, modB.userId(), "MODERATOR", 204);

		ban(modA, communityId, owner.userId(), 403);
		ban(modA, communityId, modB.userId(), 403);
		// Właściciel może zbanować moderatora — inaczej nie miałby jak odwołać
		// kogoś, komu sam dał uprawnienia.
		ban(owner, communityId, modB.userId(), 204);
	}

	@Test
	void unbanRestoresTheAbilityToJoin() throws Exception {
		TestUser owner = user("unban-owner@songly.io");
		TestUser user = user("unban-user@songly.io");
		String communityId = createCommunity(owner, "Ulaskawieni");
		join(user, communityId, 204);
		ban(owner, communityId, user.userId(), 204);

		mockMvc.perform(delete("/api/v1/communities/" + communityId + "/bans/" + user.userId()).header("Authorization",
				"Bearer " + owner.token())).andExpect(status().isNoContent());

		join(user, communityId, 204);
	}

	/** Pełna pętla: zgłoszenie trafia do kolejki i da się je rozpatrzyć. */
	@Test
	void reportedPostReachesTheQueueAndCanBeDismissed() throws Exception {
		TestUser owner = user("rep-owner@songly.io");
		TestUser author = user("rep-author@songly.io");
		TestUser reporter = user("rep-reporter@songly.io");
		String communityId = createCommunity(owner, "Zgloszenia");
		join(author, communityId, 204);
		String postId = createPost(author, communityId, "sporny post");

		report(reporter, postId, 202);

		String reportId = objectMapper.readTree(mockMvc
				.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
						"Bearer " + owner.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].reason").value("SPAM"))
				.andExpect(jsonPath("$.content[0].reportCount").value(1))
				.andExpect(jsonPath("$.content[0].post.content").value("sporny post")).andReturn().getResponse()
				.getContentAsString()).get("content").get(0).get("id").asText();

		mockMvc.perform(post("/api/v1/communities/" + communityId + "/post-reports/" + reportId + "/dismissal")
				.header("Authorization", "Bearer " + owner.token())).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
				"Bearer " + owner.token())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));
	}

	/**
	 * Skasowanie posta zabiera jego zgłoszenia — bez posta nie ma czego moderować,
	 * a zgłoszenie wskazujące w pustkę zostawałoby w kolejce na zawsze.
	 */
	@Test
	void deletingReportedPost_clearsItsReports() throws Exception {
		TestUser owner = user("rep-del-owner@songly.io");
		TestUser author = user("rep-del-author@songly.io");
		TestUser reporter = user("rep-del-reporter@songly.io");
		String communityId = createCommunity(owner, "Kasowanie-zgloszen");
		join(author, communityId, 204);
		String postId = createPost(author, communityId, "do usuniecia");
		report(reporter, postId, 202);

		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + owner.token()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
				"Bearer " + owner.token())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void reportingRules_ownPostAndDuplicatesAndNonCommunityPosts() throws Exception {
		TestUser owner = user("rep-rules-owner@songly.io");
		TestUser reporter = user("rep-rules-reporter@songly.io");
		String communityId = createCommunity(owner, "Reguly-zgloszen");
		String postId = createPost(owner, communityId, "post wlasciciela");

		report(owner, postId, 400);
		report(reporter, postId, 202);
		report(reporter, postId, 409);

		// Zwykły post nie ma dziś nikogo, kto miałby prawo rozpatrzyć zgłoszenie —
		// moderacja jest społecznościowa, nie globalna (świadomy zakres ADR 0011).
		String plainPostId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + owner.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"zwykly post"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(
				post("/api/v1/posts/" + plainPostId + "/reports").header("Authorization", "Bearer " + reporter.token())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"reason":"SPAM"}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("POST_NOT_IN_COMMUNITY"));
	}

	/** Kolejka jest dla moderatorów — zwykły członek jej nie ogląda. */
	@Test
	void queueIsVisibleOnlyToModerators() throws Exception {
		TestUser owner = user("queue-owner@songly.io");
		TestUser member = user("queue-member@songly.io");
		String communityId = createCommunity(owner, "Kolejka-prywatna");
		join(member, communityId, 204);

		mockMvc.perform(get("/api/v1/communities/" + communityId + "/post-reports").header("Authorization",
				"Bearer " + member.token())).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("NOT_COMMUNITY_MODERATOR"));
		mockMvc.perform(
				get("/api/v1/communities/" + communityId + "/bans").header("Authorization", "Bearer " + member.token()))
				.andExpect(status().isForbidden());
	}
}
