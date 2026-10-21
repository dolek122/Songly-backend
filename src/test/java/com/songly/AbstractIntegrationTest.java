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

/**
 * Kontenery uruchamiane raz na cały przebieg testów (singleton container
 * pattern) — celowo bez {@code @Testcontainers}/{@code @Container}, bo ten cykl
 * życia zatrzymywałby je po pierwszej klasie testowej, która z nich skorzysta,
 * psując kontekst Springa buforowany dla kolejnych klas. Zamiast tego start
 * ręczny raz w bloku statycznym; sprzątanie robi Ryuk przy zakończeniu JVM.
 *
 * <p>
 * Atrapa kanału e-mail jest wpięta <b>globalnie</b>, bo od czasu wprowadzenia
 * weryfikacji adresu (ADR 0009) każdy test, który potrzebuje zalogowanego
 * użytkownika, musi przejść przez wiadomość z linkiem — token nie istnieje
 * nigdzie indziej w postaci jawnej.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({RecordingMailSenderConfig.class, RecordingPushSenderConfig.class})
/*
 * Mała pula połączeń jest tu warunkiem przejścia całego przebiegu, nie
 * optymalizacją. Każda kombinacja @TestPropertySource to osobny kontekst
 * Springa, a każdy kontekst zostaje w cache'u razem ze swoją pulą Hikari — przy
 * domyślnych 10 połączeniach kilkanaście kontekstów wyczerpuje max_connections
 * Postgresa i kontekst utworzony jako ostatni pada na "too many clients". Objaw
 * jest przy tym mylący: pojedyncza klasa uruchomiona osobno przechodzi, a pełny
 * przebieg wywala losową klasę na "Failed to load ApplicationContext".
 *
 * Właściwości dziedziczą się do podklas i są takie same dla wszystkich, więc
 * nie dokładają ani jednego kontekstu.
 *
 * Limit zapisów per użytkownik (ADR 0034) jest wyłączony tutaj, a nie w każdej
 * klasie osobno jak limit per IP: testy przepływów robią jednym kontem serie
 * zapisów, a limit trafiony w połowie scenariusza dawałby 429 bez związku z
 * tematem testu. Włącza go wyłącznie UserRateLimitingIntegrationTest.
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
		"spring.datasource.hikari.minimum-idle=0", "songly.security.user-rate-limit.enabled=false"})
public abstract class AbstractIntegrationTest {

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	private static final String STORAGE_BUCKET = "songly-media";

	private static final String STORAGE_USER = "songly";

	private static final String STORAGE_PASSWORD = "songly-dev-secret";

	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@ServiceConnection
	static final GenericContainer redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379);

	/**
	 * Prawdziwy magazyn zgodny z S3, z tego samego powodu, dla którego baza to
	 * Postgres w kontenerze, a nie H2 (Claude.md §11): atrapa w pamięci
	 * potwierdzałaby wyłącznie, że kod woła własną atrapę. Tutaj naprawdę zależy
	 * nam na zachowaniu po tamtej stronie — na tym, że plik da się potem pobrać
	 * publicznym adresem.
	 *
	 * <p>
	 * Obraz <b>Chainguard</b>, nie oficjalny, i to nie jest kwestia gustu: MinIO
	 * wycofało {@code minio/minio} z Docker Huba („pull access denied"), a potem
	 * zamknęło anonimowe pobieranie także z quay.io (401 Unauthorized). Chainguard
	 * buduje ten sam serwer MinIO, ale w darmowym planie publikuje wyłącznie
	 * {@code :latest} — wersja nie jest przypięta. Nie wracać do rejestrów MinIO
	 * bez sprawdzenia, że obraz da się pobrać bez logowania: lokalnie awaria jest
	 * niewidoczna, bo obraz siedzi w cache'u Dockera, i czerwienieje dopiero CI na
	 * czystym runnerze.
	 *
	 * <p>
	 * {@code asCompatibleSubstituteFor} jest konieczne, bo {@code MinIOContainer}
	 * sprawdza nazwę obrazu i odrzuca każdą inną niż {@code minio/minio}.
	 */
	static final MinIOContainer minio = new MinIOContainer(
			DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"))
			.withUserName(STORAGE_USER).withPassword(STORAGE_PASSWORD);

	static {
		postgres.start();
		redis.start();
		minio.start();
		createPublicBucket();
	}

	/**
	 * Bucket z anonimowym odczytem — avatary i zdjęcia w postach są publiczne z
	 * założenia, bo klient pobiera je wprost, bez naszego pośrednictwa. Test, który
	 * czyta pliki uwierzytelnionym klientem SDK, nie sprawdziłby tego, co robi
	 * przeglądarka.
	 */
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

	/**
	 * Zakłada konto i od razu potwierdza adres, zwracając odpowiedź weryfikacji —
	 * czyli tę samą {@code AuthTokensResponse}, którą przed ADR 0009 zwracała sama
	 * rejestracja.
	 *
	 * <p>
	 * Helper siedzi w klasie bazowej, bo inaczej dwadzieścia kilka testów musiałoby
	 * powtarzać u siebie dwa żądania i wyciąganie tokenu z maila. To nie jest
	 * obejście weryfikacji: każdy z tych testów naprawdę przechodzi pełną ścieżkę,
	 * łącznie z odczytaniem linku z wysłanej wiadomości.
	 *
	 * <p>
	 * <b>Adres musi być unikalny w całej suicie, nie tylko w swojej klasie.</b>
	 * Baza jest jedna na cały przebieg, więc adres użyty wcześniej przez inną klasę
	 * ma już konto — a wtedy rejestracja idzie gałęzią „ten adres jest zajęty" i
	 * wysyła ostrzeżenie zamiast linku (ADR 0009). Objaw jest mylący: test
	 * przechodzi uruchomiony sam, a pada w pełnym przebiegu, i to na asercji o
	 * treści wiadomości, która z jego tematem nie ma nic wspólnego.
	 */
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

	/**
	 * Nadaje kontu nazwę wyświetlaną równą części adresu przed „@” — jawnie, jak
	 * zrobiłby to użytkownik w aplikacji.
	 *
	 * <p>
	 * Domyślna nazwa to od ADR 0046 losowe „Użytkownik 123456” (wcześniej była to
	 * właśnie część adresu, co wystawiało ją publicznie). Testy potrzebują nazw
	 * przewidywalnych i czytelnych — szukają po nich i porównują je — więc nadajemy
	 * je tutaj, zamiast polegać na dawnym zachowaniu. Samą domyślną nazwę sprawdza
	 * {@code DefaultDisplayNameIntegrationTest}, który tego pomocnika nie używa.
	 */
	private void nameAfterEmail(MvcResult verified, String email) throws Exception {
		String accessToken = objectMapper.readTree(verified.getResponse().getContentAsString()).get("accessToken")
				.asText();
		sharedMockMvc
				.perform(put("/api/v1/profiles/me").header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON).content("""
								{"displayName":"%s"}""".formatted(email.substring(0, email.indexOf('@')))))
				.andExpect(status().isOk());
	}

	/** Token istnieje w postaci jawnej wyłącznie w wysłanej wiadomości. */
	protected String verificationTokenFor(String email) {
		Matcher matcher = TOKEN_IN_LINK.matcher(mails.lastTo(email).body());
		assertThat(matcher.find()).as("Wiadomość do %s powinna zawierać link z tokenem", email).isTrue();
		return matcher.group(1);
	}
}
