package com.songly.content;

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
class FeedFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String accessToken, String userId) {
	}

	private TestUser registerUser(String email) throws Exception {
		MvcResult registerResult = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isCreated())
				.andReturn();
		String accessToken = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult profileResult = mockMvc
				.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String userId = objectMapper.readTree(profileResult.getResponse().getContentAsString()).get("userId").asText();
		return new TestUser(accessToken, userId);
	}

	private void makeFriends(TestUser from, TestUser to) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + from.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"addresseeId":"%s"}""".formatted(to.userId())))
				.andExpect(status().isCreated()).andReturn();
		String requestId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/%s/accept".formatted(requestId)).header("Authorization",
				"Bearer " + to.accessToken())).andExpect(status().isOk());
	}

	private void createPost(TestUser author, String content) throws Exception {
		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"%s"}""".formatted(content))).andExpect(status().isCreated());
	}

	@Test
	void feed_containsOwnAndFriendsPosts_butNotStrangers() throws Exception {
		TestUser ada = registerUser("feed-ada@songly.io");
		TestUser bono = registerUser("feed-bono@songly.io");
		TestUser stranger = registerUser("feed-stranger@songly.io");

		makeFriends(ada, bono);

		createPost(ada, "post Ady");
		createPost(bono, "post Bono");
		createPost(stranger, "post obcego");

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2)).andExpect(jsonPath(
						"$.content[*].content", org.hamcrest.Matchers.containsInAnyOrder("post Ady", "post Bono")));

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + stranger.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].content").value("post obcego"));
	}

	@Test
	void feed_containsPostsSharedByFriends() throws Exception {
		TestUser ada = registerUser("feed-share-ada@songly.io");
		TestUser bono = registerUser("feed-share-bono@songly.io");
		TestUser stranger = registerUser("feed-share-stranger@songly.io");

		makeFriends(ada, bono);
		createPost(stranger, "post obcego do udostępnienia");

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));

		MvcResult postsResult = mockMvc.perform(get("/api/v1/posts?authorId=" + stranger.userId())
				.header("Authorization", "Bearer " + bono.accessToken())).andExpect(status().isOk()).andReturn();
		String postId = objectMapper.readTree(postsResult.getResponse().getContentAsString()).get("content").get(0)
				.get("id").asText();

		mockMvc.perform(
				put("/api/v1/posts/" + postId + "/shares").header("Authorization", "Bearer " + bono.accessToken()))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/feed").header("Authorization", "Bearer " + ada.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(postId))
				.andExpect(jsonPath("$.content[0].shareCount").value(1));
	}

	@Test
	void feed_requiresAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/feed")).andExpect(status().isUnauthorized());
	}
}
