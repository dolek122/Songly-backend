package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Moderatorzy usuwają komentarze w swojej społeczności. Wcześniej jedyną
 * odpowiedzią na komentarz trolla było skasowanie całego posta, czyli ukaranie
 * jego autora za cudzą treść.
 */
@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class CommentModerationIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String token, String userId, String email) {
	}

	private TestUser user(String email) throws Exception {
		String token = objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
		String userId = json(get("/api/v1/profiles/me"), token).get("userId").asText();
		return new TestUser(token, userId, email);
	}

	private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String token) throws Exception {
		return objectMapper.readTree(mockMvc.perform(request.header("Authorization", "Bearer " + token))
				.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
	}

	private String createCommunity(TestUser owner, String name) throws Exception {
		return json(post("/api/v1/communities").contentType(MediaType.APPLICATION_JSON).content("""
				{"name":"%s","description":"opis"}""".formatted(name)), owner.token()).get("id").asText();
	}

	private void join(TestUser user, String communityId) throws Exception {
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + user.token())).andExpect(status().isNoContent());
	}

	private String createPost(TestUser author, String path) throws Exception {
		return json(post(path).contentType(MediaType.APPLICATION_JSON).content("""
				{"content":"post"}"""), author.token()).get("id").asText();
	}

	private String comment(TestUser author, String postId) throws Exception {
		return json(post("/api/v1/posts/" + postId + "/comments").contentType(MediaType.APPLICATION_JSON).content("""
				{"content":"komentarz"}"""), author.token()).get("id").asText();
	}

	private void deleteComment(TestUser requester, String postId, String commentId, int expectedStatus)
			throws Exception {
		mockMvc.perform(delete("/api/v1/posts/" + postId + "/comments/" + commentId).header("Authorization",
				"Bearer " + requester.token())).andExpect(status().is(expectedStatus));
	}

	@Test
	void moderatorRemovesOthersComment_andTheAuthorIsToldWithoutNamingTheModerator() throws Exception {
		TestUser owner = user("comment-mod-owner@songly.io");
		TestUser moderator = user("comment-mod-moderator@songly.io");
		TestUser author = user("comment-mod-author@songly.io");
		TestUser troll = user("comment-mod-troll@songly.io");
		String communityId = createCommunity(owner, "Komentarze-moderacja");
		join(moderator, communityId);
		join(author, communityId);
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/members/" + moderator.userId() + "/role")
				.header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON).content("""
						{"role":"MODERATOR"}""")).andExpect(status().isNoContent());
		String postId = createPost(author, "/api/v1/communities/" + communityId + "/posts");
		String commentId = comment(troll, postId);
		// Ten sam moderator zostawia też zwykły ślad u autora komentarza — jego nazwa
		// trafia wtedy na tę samą stronę skrzynki i nie może przeciec do decyzji.
		String trollsOwnPost = createPost(troll, "/api/v1/posts");
		mockMvc.perform(
				put("/api/v1/posts/" + trollsOwnPost + "/likes").header("Authorization", "Bearer " + moderator.token()))
				.andExpect(status().isNoContent());
		mails.clearFor(troll.email());

		deleteComment(moderator, postId, commentId, 204);

		mockMvc.perform(
				get("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + author.token()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
		JsonNode inbox = json(get("/api/v1/notifications"), troll.token()).get("content");
		JsonNode removal = inbox.get(0);
		assertThat(removal.get("type").asText()).isEqualTo("COMMUNITY_COMMENT_REMOVED");
		assertThat(removal.get("resourceId").asText()).isEqualTo(postId);
		// Decyzja należy do społeczności, nie do osoby (ADR 0015).
		assertThat(removal.get("actorDisplayName").isNull()).isTrue();
		assertThat(removal.get("actorAvatarUrl").isNull()).isTrue();
		JsonNode like = inbox.get(1);
		assertThat(like.get("type").asText()).isEqualTo("POST_LIKED");
		assertThat(like.get("actorDisplayName").asText()).isEqualTo("comment-mod-moderator");
		assertThat(mails.lastTo(troll.email()).subject()).contains("komentarz");
		JsonNode log = json(get("/api/v1/communities/" + communityId + "/moderation-log"), owner.token())
				.get("content");
		assertThat(log.findValuesAsString("action")).contains("COMMUNITY_COMMENT_REMOVED");
	}

	@Test
	void ordinaryMemberCannotRemoveOthersComment() throws Exception {
		TestUser owner = user("comment-mod-plain-owner@songly.io");
		TestUser author = user("comment-mod-plain-author@songly.io");
		TestUser other = user("comment-mod-plain-other@songly.io");
		String communityId = createCommunity(owner, "Komentarze-zwykly-czlonek");
		join(author, communityId);
		join(other, communityId);
		String postId = createPost(author, "/api/v1/communities/" + communityId + "/posts");
		String commentId = comment(author, postId);

		deleteComment(other, postId, commentId, 403);
	}

	/**
	 * Uprawnienia moderatora nie wychodzą poza jego społeczność — pod zwykłym
	 * postem chroni wyłącznie autorstwo (§8).
	 */
	@Test
	void moderationDoesNotReachCommentsUnderRegularPosts() throws Exception {
		TestUser owner = user("comment-mod-regular-owner@songly.io");
		TestUser author = user("comment-mod-regular-author@songly.io");
		createCommunity(owner, "Komentarze-poza-spolecznoscia");
		String postId = createPost(author, "/api/v1/posts");
		String commentId = comment(author, postId);

		deleteComment(owner, postId, commentId, 403);
	}

	/** Skasowanie własnego komentarza nie jest decyzją moderacyjną. */
	@Test
	void removingOwnCommentNotifiesNobody() throws Exception {
		TestUser owner = user("comment-mod-own-owner@songly.io");
		String communityId = createCommunity(owner, "Komentarze-wlasny");
		String postId = createPost(owner, "/api/v1/communities/" + communityId + "/posts");
		String commentId = comment(owner, postId);

		deleteComment(owner, postId, commentId, 204);

		JsonNode log = json(get("/api/v1/communities/" + communityId + "/moderation-log"), owner.token())
				.get("content");
		assertThat(log.findValuesAsString("action")).doesNotContain("COMMUNITY_COMMENT_REMOVED");
	}
}
