package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.AbstractIntegrationTest;
import com.songly.TestImages;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "songly.security.rate-limit.enabled=false")
class PostImageIntegrationTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PostImageUploads postImageUploads;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static byte[] pngBytes() {
		return TestImages.png();
	}

	private String accessToken(String email) throws Exception {
		return objectMapper.readTree(registerAndVerify(email, "password123").getResponse().getContentAsString())
				.get("accessToken").asText();
	}

	private JsonNode uploadImage(String token) throws Exception {
		return uploadImage(token, pngBytes());
	}

	private JsonNode uploadImage(String token, byte[] content) throws Exception {
		return objectMapper.readTree(mockMvc
				.perform(multipart("/api/v1/posts/image")
						.file(new MockMultipartFile("file", "zdjecie.png", MediaType.IMAGE_PNG_VALUE, content))
						.header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode createPost(String token, String content, String imageKey, int expectedStatus) throws Exception {
		String body = imageKey == null ? """
				{"content":"%s"}""".formatted(content) : """
				{"content":"%s","imageKey":"%s"}""".formatted(content, imageKey);
		String response = mockMvc
				.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private HttpResponse<byte[]> fetchAnonymously(String url) throws IOException, InterruptedException {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
					HttpResponse.BodyHandlers.ofByteArray());
		}
	}

	@Test
	void uploadedImage_isAttachedToThePostAndPubliclyFetchable() throws Exception {
		String token = accessToken("post-image-happy@songly.io");
		String key = uploadImage(token).get("key").asText();

		JsonNode created = createPost(token, "Nowy album", key, 201);

		String imageUrl = created.get("imageUrl").asText();
		assertThat(fetchAnonymously(imageUrl).body()).isEqualTo(pngBytes());
		mockMvc.perform(get("/api/v1/posts/" + created.get("id").asText()).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(imageUrl));
	}

	@Test
	void uploadedImage_isStoredWithoutCameraMetadata() throws Exception {
		String token = accessToken("post-image-exif@songly.io");

		String url = uploadImage(token, TestImages.jpegWithExif(6)).get("url").asText();

		byte[] stored = fetchAnonymously(url).body();
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(TestImages.SECRET);
		assertThat(ImageIO.read(new ByteArrayInputStream(stored)).getWidth()).isEqualTo(2);
	}

	@Test
	void postWithoutImage_hasNullImageUrl() throws Exception {
		String token = accessToken("post-image-none@songly.io");

		assertThat(createPost(token, "Bez zdjecia", null, 201).get("imageUrl").isNull()).isTrue();
	}

	@Test
	void anotherUsersImageKey_isRejected() throws Exception {
		String owner = accessToken("post-image-owner@songly.io");
		String thief = accessToken("post-image-thief@songly.io");
		String foreignKey = uploadImage(owner).get("key").asText();

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + thief)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"nie moje","imageKey":"%s"}""".formatted(foreignKey)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	@Test
	void fabricatedImageKey_isRejected() throws Exception {
		String token = accessToken("post-image-fake@songly.io");

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"zmyslone","imageKey":"posts/../../etc/passwd"}"""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	@Test
	void ownPrefixWithTraversal_isRejected() throws Exception {
		String owner = accessToken("post-image-trav-owner@songly.io");
		String thief = accessToken("post-image-trav-thief@songly.io");
		String foreignKey = uploadImage(owner).get("key").asText();
		String thiefId = objectMapper
				.readTree(mockMvc.perform(get("/api/v1/profiles/me").header("Authorization", "Bearer " + thief))
						.andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
				.get("userId").asText();

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + thief)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"nie moje","imageKey":"posts/%s/../../%s"}""".formatted(thiefId, foreignKey)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	@Test
	void deletingPost_removesItsImage() throws Exception {
		String token = accessToken("post-image-delete@songly.io");
		JsonNode created = createPost(token, "Do skasowania", uploadImage(token).get("key").asText(), 201);
		String imageUrl = created.get("imageUrl").asText();
		assertThat(fetchAnonymously(imageUrl).statusCode()).isEqualTo(200);

		mockMvc.perform(
				delete("/api/v1/posts/" + created.get("id").asText()).header("Authorization", "Bearer " + token))
				.andExpect(status().isNoContent());

		assertThat(fetchAnonymously(imageUrl).statusCode()).isEqualTo(404);
	}

	@Test
	void deletingAccount_removesPostImages() throws Exception {
		String token = accessToken("post-image-account@songly.io");
		String imageUrl = createPost(token, "Zniknie z kontem", uploadImage(token).get("key").asText(), 201)
				.get("imageUrl").asText();

		mockMvc.perform(delete("/api/v1/auth/me").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"password":"password123"}""")).andExpect(status().isNoContent());

		assertThat(fetchAnonymously(imageUrl).statusCode()).isEqualTo(404);
	}

	@Test
	void nonImageUploadIsRejected() throws Exception {
		String token = accessToken("post-image-notimage@songly.io");

		mockMvc.perform(multipart("/api/v1/posts/image")
				.file(new MockMultipartFile("file", "plik.png", MediaType.IMAGE_PNG_VALUE, "zwykly tekst".getBytes()))
				.header("Authorization", "Bearer " + token)).andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("UNSUPPORTED_IMAGE"));
	}

	@Test
	void abandonedUpload_isRemovedFromStorage_andCanNoLongerBeAttached() throws Exception {
		String token = accessToken("post-image-abandoned@songly.io");
		JsonNode upload = uploadImage(token);
		String key = upload.get("key").asText();
		age(key);

		postImageUploads.removeAbandoned(Instant.now());

		assertThat(fetchAnonymously(upload.get("url").asText()).statusCode()).isNotEqualTo(200);
		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"za pozno","imageKey":"%s"}""".formatted(key))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"))
				.andExpect(jsonPath("$.message").value("Zdjęcie wygasło albo zostało już użyte. Wgraj je ponownie."));
	}

	@Test
	void cleanup_leavesRecentUploadsAndPublishedImagesAlone() throws Exception {
		String token = accessToken("post-image-survivor@songly.io");
		JsonNode recent = uploadImage(token);
		String publishedKey = uploadImage(token).get("key").asText();
		String publishedUrl = createPost(token, "Opublikowane", publishedKey, 201).get("imageUrl").asText();
		age(publishedKey);

		postImageUploads.removeAbandoned(Instant.now());

		assertThat(fetchAnonymously(publishedUrl).statusCode()).isEqualTo(200);
		assertThat(fetchAnonymously(recent.get("url").asText()).statusCode()).isEqualTo(200);
		createPost(token, "Swieze", recent.get("key").asText(), 201);
	}

	@Test
	void sameUpload_cannotBeAttachedToTwoPosts() throws Exception {
		String token = accessToken("post-image-twice@songly.io");
		String key = uploadImage(token).get("key").asText();
		createPost(token, "Pierwszy", key, 201);

		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"Drugi","imageKey":"%s"}""".formatted(key))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
	}

	@Test
	void uploadBeyondPendingLimit_evictsTheOldest() throws Exception {
		String token = accessToken("post-image-evict@songly.io");
		JsonNode oldest = uploadImage(token);
		JsonNode secondOldest = uploadImage(token);
		for (int i = 0; i < 9; i++) {
			uploadImage(token);
		}

		assertThat(fetchAnonymously(oldest.get("url").asText()).statusCode()).isNotEqualTo(200);
		mockMvc.perform(post("/api/v1/posts").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"content":"wyparte","imageKey":"%s"}""".formatted(oldest.get("key").asText())))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_POST_IMAGE"));
		createPost(token, "Wciaz oczekuje", secondOldest.get("key").asText(), 201);
	}

	private void age(String key) {
		jdbcTemplate.update("UPDATE post_image_uploads SET created_at = ? WHERE image_key = ?",
				Instant.now().minus(2, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC), key);
	}
}
