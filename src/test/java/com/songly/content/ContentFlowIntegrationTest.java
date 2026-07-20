package com.songly.content;

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
class ContentFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private String registerAndGetAccessToken(String email) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
						{"email":"%s","password":"password123"}""".formatted(email))).andExpect(status().isCreated())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
	}

	private String createPost(String accessToken, String content) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"%s"}""".formatted(content))).andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void fullPostFlow_createLikeCommentListDelete() throws Exception {
		String author = registerAndGetAccessToken("content-author@songly.io");
		String reader = registerAndGetAccessToken("content-reader@songly.io");

		String postId = createPost(author, "Słuchajcie nowego albumu!");

		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + reader))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + reader))
				.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + reader)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"Świetny!"}""")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.authorDisplayName").value("content-reader"));

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + reader))
				.andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(1))
				.andExpect(jsonPath("$.likedByMe").value(true)).andExpect(jsonPath("$.commentCount").value(1))
				.andExpect(jsonPath("$.authorDisplayName").value("content-author"));

		mockMvc.perform(get("/api/v1/posts").header("Authorization", "Bearer " + reader)).andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(postId));

		mockMvc.perform(delete("/api/v1/posts/" + postId + "/likes").header("Authorization", "Bearer " + reader))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + reader))
				.andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(0))
				.andExpect(jsonPath("$.likedByMe").value(false));

		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + reader))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NOT_RESOURCE_OWNER"));
		mockMvc.perform(delete("/api/v1/posts/" + postId).header("Authorization", "Bearer " + author))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + author))
				.andExpect(status().isNotFound());
	}

	@Test
	void commentDeletion_onlyByCommentAuthor() throws Exception {
		String author = registerAndGetAccessToken("comment-author@songly.io");
		String other = registerAndGetAccessToken("comment-other@songly.io");
		String postId = createPost(author, "Post pod komentarze");

		MvcResult commentResult = mockMvc.perform(post("/api/v1/posts/" + postId + "/comments")
				.header("Authorization", "Bearer " + other).contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"komentarz"}""")).andExpect(status().isCreated()).andReturn();
		String commentId = objectMapper.readTree(commentResult.getResponse().getContentAsString()).get("id").asText();

		mockMvc.perform(delete("/api/v1/posts/" + postId + "/comments/" + commentId).header("Authorization",
				"Bearer " + author)).andExpect(status().isForbidden());
		mockMvc.perform(
				delete("/api/v1/posts/" + postId + "/comments/" + commentId).header("Authorization", "Bearer " + other))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/" + postId + "/comments").header("Authorization", "Bearer " + author))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}

	@Test
	void sharePost_updatesCounters_andIsIdempotent() throws Exception {
		String author = registerAndGetAccessToken("share-author@songly.io");
		String sharer = registerAndGetAccessToken("share-sharer@songly.io");
		String postId = createPost(author, "Post do udostępnienia");

		mockMvc.perform(put("/api/v1/posts/" + postId + "/shares").header("Authorization", "Bearer " + sharer))
				.andExpect(status().isNoContent());
		mockMvc.perform(put("/api/v1/posts/" + postId + "/shares").header("Authorization", "Bearer " + sharer))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + sharer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shareCount").value(1))
				.andExpect(jsonPath("$.sharedByMe").value(true));

		mockMvc.perform(delete("/api/v1/posts/" + postId + "/shares").header("Authorization", "Bearer " + sharer))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/posts/" + postId).header("Authorization", "Bearer " + sharer))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shareCount").value(0))
				.andExpect(jsonPath("$.sharedByMe").value(false));
	}

	@Test
	void postEndpoints_requireAuthentication() throws Exception {
		mockMvc.perform(get("/api/v1/posts")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/v1/posts").contentType(MediaType.APPLICATION_JSON).content("""
				{"content":"x"}""")).andExpect(status().isUnauthorized());
	}
}
