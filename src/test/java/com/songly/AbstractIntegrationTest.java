package com.songly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.RecordingMailSenderConfig.RecordingMailSender;
import com.songly.RecordingPushSenderConfig.RecordingPushSender;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Import({RecordingMailSenderConfig.class, RecordingPushSenderConfig.class})
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
		"spring.datasource.hikari.minimum-idle=0", "songly.security.user-rate-limit.enabled=false",
		"songly.notification.push.async-delivery=false"})
public abstract class AbstractIntegrationTest {

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	private static final String STORAGE_BUCKET = "songly-media";

	private static final String STORAGE_USER = "songly";

	private static final String STORAGE_PASSWORD = "songly-dev-secret";

	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@ServiceConnection
	static final GenericContainer redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379);

	static final MinIOContainer minio = new MinIOContainer(
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"))
			.withUserName(STORAGE_USER).withPassword(STORAGE_PASSWORD);

	static {
		postgres.start();
		redis.start();
		minio.start();
		createPublicBucket();
	}

	private static void createPublicBucket() {
		try (S3Client client = S3Client.builder().endpointOverride(URI.create(minio.getS3URL()))
				.region(Region.of("us-east-1"))
				.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
				.credentialsProvider(
						StaticCredentialsProvider.create(AwsBasicCredentials.create(STORAGE_USER, STORAGE_PASSWORD)))
				.build()) {
			client.createBucket(builder -> builder.bucket(STORAGE_BUCKET));
			client.putBucketPolicy(builder -> builder.bucket(STORAGE_BUCKET).policy("""
					{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*",\
					"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}""".formatted(STORAGE_BUCKET)));
		}
	}

	@DynamicPropertySource
	static void storageProperties(DynamicPropertyRegistry registry) {
		registry.add("songly.storage.endpoint", minio::getS3URL);
		registry.add("songly.storage.public-base-url", () -> minio.getS3URL() + "/" + STORAGE_BUCKET);
		registry.add("songly.storage.bucket", () -> STORAGE_BUCKET);
		registry.add("songly.storage.access-key", () -> STORAGE_USER);
		registry.add("songly.storage.secret-key", () -> STORAGE_PASSWORD);
		registry.add("songly.storage.region", () -> "us-east-1");
	}

	@Autowired
	private MockMvc sharedMockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	protected RecordingMailSender mails;

	@Autowired
	protected RecordingPushSender pushes;

	protected MvcResult registerAndVerify(String email, String password) throws Exception {
		mails.clearFor(email);
		sharedMockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(email, password))).andExpect(status().isAccepted());
		MvcResult verified = sharedMockMvc
				.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"%s"}""".formatted(verificationTokenFor(email)))).andExpect(status().isOk())
				.andReturn();
		nameAfterEmail(verified, email);
		return verified;
	}

	private void nameAfterEmail(MvcResult verified, String email) throws Exception {
		String accessToken = objectMapper.readTree(verified.getResponse().getContentAsString()).get("accessToken")
				.asText();
		sharedMockMvc
				.perform(put("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"displayName":"%s"}""".formatted(email.substring(0, email.indexOf('@')))))
				.andExpect(status().isOk());
	}

	protected String verificationTokenFor(String email) {
		Matcher matcher = TOKEN_IN_LINK.matcher(mails.lastTo(email).body());
		assertThat(matcher.find()).as("Wiadomość do %s powinna zawierać link z tokenem", email).isTrue();
		return matcher.group(1);
	}
}
