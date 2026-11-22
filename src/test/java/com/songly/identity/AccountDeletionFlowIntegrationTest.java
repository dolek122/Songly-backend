package com.songly.identity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.TestImages;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class AccountDeletionFlowIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	private record TestUser(String accessToken, String userId) {
	}

	private TestUser registerUser(String email) throws Exception {
		MvcResult registerResult = registerAndVerify(email, "password123");
		String accessToken = objectMapper.readTree(registerResult.getResponse().getContentAsString()).get("accessToken")
				.asText();
		MvcResult profileResult = mockMvc
				.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn();
		String userId = objectMapper.readTree(profileResult.getResponse().getContentAsString()).get("userId").asText();
		return new TestUser(accessToken, userId);
	}

	private String createPost(TestUser author, String content) throws Exception {
		MvcResult result = mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + author.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"%s"}""".formatted(content)))
				.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
	}

	@Test
	void deletingAccount_removesDataAcrossEveryModule_withoutForeignKeyViolations() throws Exception {
		TestUser leaving = registerUser("leaving-user@songly.io");
		TestUser staying = registerUser("staying-user@songly.io");
		TestUser stranger = registerUser("stranger-user@songly.io");

		MvcResult genresResult = mockMvc
				.perform(get("/api/v1/genres?size=2").header("Authorization", "Bearer " + leaving.accessToken()))
				.andExpect(status().isOk()).andReturn();
		String genreId = objectMapper.readTree(genresResult.getResponse().getContentAsString()).get("content").get(0)
				.get("id").asText();
		mockMvc.perform(put("/api/v1/profiles/me/genres").header("Authorization", "Bearer " + leaving.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"genreIds":["%s"]}""".formatted(genreId))).andExpect(status().isNoContent());

		String ownPostId = createPost(leaving, "post usera, ktory zaraz zniknie");
		String otherPostId = createPost(staying, "post kogos innego");
		mockMvc.perform(put("/api/v1/posts/" + otherPostId + "/likes").header("Authorization",
				"Bearer " + leaving.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(post("/api/v1/posts/" + otherPostId + "/comments")
				.header("Authorization", "Bearer " + leaving.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"content":"komentarz przed usunieciem konta"}""")).andExpect(status().isCreated());
		mockMvc.perform(put("/api/v1/posts/" + otherPostId + "/shares").header("Authorization",
				"Bearer " + leaving.accessToken())).andExpect(status().isNoContent());
		mockMvc.perform(
				put("/api/v1/posts/" + ownPostId + "/likes").header("Authorization", "Bearer " + staying.accessToken()))
				.andExpect(status().isNoContent());

		String requestId = objectMapper
				.readTree(mockMvc
						.perform(post("/api/v1/friends/requests")
								.header("Authorization", "Bearer " + leaving.accessToken())
								.contentType(MediaType.APPLICATION_JSON).content("""
										{"addresseeId":"%s"}""".formatted(staying.userId())))
						.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
				.get("id").asText();
		mockMvc.perform(post("/api/v1/friends/requests/" + requestId + "/accept").header("Authorization",
				"Bearer " + staying.accessToken())).andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/friends/requests").header("Authorization", "Bearer " + leaving.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"addresseeId":"%s"}""".formatted(stranger.userId()))).andExpect(status().isCreated());

		String communityId = objectMapper
				.readTree(mockMvc
						.perform(post("/api/v1/communities").header("Authorization", "Bearer " + leaving.accessToken())
								.contentType(MediaType.APPLICATION_JSON).content("""
										{"name":"Community-znikajaca","description":"znika razem z ownerem"}"""))
						.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
				.get("id").asText();
		mockMvc.perform(put("/api/v1/communities/" + communityId + "/membership").header("Authorization",
				"Bearer " + staying.accessToken())).andExpect(status().isNoContent());

		mockMvc.perform(put("/api/v1/now-playing/settings").header("Authorization", "Bearer " + leaving.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"shareWithFriends":false}""")).andExpect(status().isNoContent());

		mockMvc.perform(
				put("/api/v1/notifications/mail-preferences").header("Authorization", "Bearer " + leaving.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"decisionsAboutMe":false,"moderationQueue":false}"""))
				.andExpect(status().isNoContent());
		mockMvc.perform(
				put("/api/v1/notifications/push-devices").header("Authorization", "Bearer " + leaving.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"token":"leaving-user-device","platform":"ANDROID"}"""))
				.andExpect(status().isNoContent());

		mockMvc.perform(put("/api/v1/communities/" + communityId + "/bans/" + stranger.userId())
				.header("Authorization", "Bearer " + leaving.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"test"}""")).andExpect(status().isNoContent());
		String communityPostId = objectMapper.readTree(mockMvc
				.perform(post("/api/v1/communities/" + communityId + "/posts")
						.header("Authorization", "Bearer " + staying.accessToken())
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"content":"post do zgloszenia"}"""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
		mockMvc.perform(post("/api/v1/posts/" + communityPostId + "/reports")
				.header("Authorization", "Bearer " + leaving.accessToken()).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"SPAM"}""")).andExpect(status().isAccepted());

		mockMvc.perform(multipart("/api/v1/posts/image")
				.file(new MockMultipartFile("file", "porzucone.png", MediaType.IMAGE_PNG_VALUE, TestImages.png()))
				.header("Authorization", "Bearer " + leaving.accessToken())).andExpect(status().isOk());

		mockMvc.perform(post("/api/v1/auth/password/reset-request").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"leaving-user@songly.io"}""")).andExpect(status().isAccepted());

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + leaving.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"zle-haslo"}""")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + leaving.accessToken())
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());

		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"leaving-user@songly.io","password":"password123"}""")).andExpect(status().isUnauthorized());

		mockMvc.perform(get("/api/v1/friends").header("Authorization", "Bearer " + staying.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
		mockMvc.perform(get("/api/v1/friends/requests?direction=INCOMING").header("Authorization",
				"Bearer " + stranger.accessToken())).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));
		mockMvc.perform(get("/api/v1/posts/" + otherPostId).header("Authorization", "Bearer " + staying.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(0))
				.andExpect(jsonPath("$.commentCount").value(0)).andExpect(jsonPath("$.shareCount").value(0));
		mockMvc.perform(get("/api/v1/posts/" + ownPostId).header("Authorization", "Bearer " + staying.accessToken()))
				.andExpect(status().isNotFound());
		mockMvc.perform(
				get("/api/v1/communities/" + communityId).header("Authorization", "Bearer " + staying.accessToken()))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/api/v1/communities/mine").header("Authorization", "Bearer " + staying.accessToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
	}
}
