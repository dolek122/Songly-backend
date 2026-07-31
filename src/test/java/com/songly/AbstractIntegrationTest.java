package com.songly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.songly.RecordingMailSenderConfig.RecordingMailSender;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
@Import(RecordingMailSenderConfig.class)
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
 */
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
		"spring.datasource.hikari.minimum-idle=0"})
public abstract class AbstractIntegrationTest {

	private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	@ServiceConnection
	static final GenericContainer redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379);

	static {
		postgres.start();
		redis.start();
	}

	@Autowired
	private MockMvc sharedMockMvc;

	@Autowired
	protected RecordingMailSender mails;

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
	 */
	protected MvcResult registerAndVerify(String email, String password) throws Exception {
		mails.clearFor(email);
		sharedMockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"email":"%s","password":"%s"}""".formatted(email, password))).andExpect(status().isAccepted());
		return sharedMockMvc
				.perform(post("/api/v1/auth/register/verify").contentType(MediaType.APPLICATION_JSON).content("""
						{"token":"%s"}""".formatted(verificationTokenFor(email)))).andExpect(status().isOk())
				.andReturn();
	}

	/** Token istnieje w postaci jawnej wyłącznie w wysłanej wiadomości. */
	protected String verificationTokenFor(String email) {
		Matcher matcher = TOKEN_IN_LINK.matcher(mails.lastTo(email).body());
		assertThat(matcher.find()).as("Wiadomość do %s powinna zawierać link z tokenem", email).isTrue();
		return matcher.group(1);
	}
}
