package com.songly.identity;

import com.songly.identity.dto.AccountDeletionLinkRequest;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Publiczna strona usuwania konta (ADR 0043) — jedyne miejsce, w którym backend
 * wydaje HTML, a nie JSON. Adres tej strony wpisuje się w formularzu
 * „Bezpieczeństwo danych" w Google Play.
 *
 * <p>
 * Poza {@code /api/v1} i poza kontraktem OpenAPI ({@code @Hidden}): to strona
 * dla człowieka w przeglądarce, a nie operacja dla klienta mobilnego, więc nie
 * ma czego pilnować w kontrakcie z Songly-mobile.
 *
 * <p>
 * Bez ochrony CSRF i to jest świadome. Podrobiony formularz prośby może co
 * najwyżej wysłać link na wskazany przez napastnika adres — tyle samo zrobi on
 * sam, wpisując ten adres. Podrobione potwierdzenie wymaga tokenu, którego
 * napastnik nie zna. Nagłówki z {@link #html} zabraniają za to osadzania strony
 * w ramce i ładowania czegokolwiek z zewnątrz.
 */
@Hidden
@Controller
class AccountDeletionPageController {

	/**
	 * Strona nie ładuje niczego spoza siebie, więc polityka może zabronić prawie
	 * wszystkiego. {@code form-action 'self'} to kluczowa część: wstrzyknięty
	 * formularz nie wyśle tokenu nigdzie indziej.
	 */
	private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; "
			+ "form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

	private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

	private final AccountDeletionService accountDeletionService;
	private final AccountDeletionProperties properties;

	AccountDeletionPageController(AccountDeletionService accountDeletionService, AccountDeletionProperties properties) {
		this.accountDeletionService = accountDeletionService;
		this.properties = properties;
	}

	@GetMapping(AccountDeletionPages.START_PATH)
	ResponseEntity<String> start() {
		return html(HttpStatus.OK, AccountDeletionPages.start(ttlMinutes(), null));
	}

	/**
	 * Zawsze ta sama strona „sprawdź skrzynkę" — niezależnie od tego, czy konto
	 * istnieje (ADR 0008).
	 */
	@PostMapping(path = AccountDeletionPages.START_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	ResponseEntity<String> requestLink(@Valid @ModelAttribute AccountDeletionLinkRequest form, BindingResult binding) {
		if (binding.hasErrors()) {
			return html(HttpStatus.BAD_REQUEST,
					AccountDeletionPages.start(ttlMinutes(), "Podaj poprawny adres e-mail."));
		}
		accountDeletionService.requestDeletion(form.email());
		return html(HttpStatus.OK, AccountDeletionPages.linkSent(ttlMinutes()));
	}

	/**
	 * Otwarcie linku z maila niczego nie usuwa — pokazuje przycisk. Skanery poczty
	 * i podglądy linków otwierają adresy z wiadomości bez udziału człowieka.
	 */
	@GetMapping(AccountDeletionPages.CONFIRM_PATH)
	ResponseEntity<String> confirmPage(@RequestParam(required = false) String token) {
		return accountDeletionService.isValid(token)
				? html(HttpStatus.OK, AccountDeletionPages.confirm(token))
				: html(HttpStatus.NOT_FOUND, AccountDeletionPages.invalidLink(ttlMinutes()));
	}

	@PostMapping(path = AccountDeletionPages.CONFIRM_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	ResponseEntity<String> confirm(@RequestParam(required = false) String token) {
		return accountDeletionService.confirmDeletion(token)
				? html(HttpStatus.OK, AccountDeletionPages.deleted())
				: html(HttpStatus.NOT_FOUND, AccountDeletionPages.invalidLink(ttlMinutes()));
	}

	private long ttlMinutes() {
		return properties.getTtl().toMinutes();
	}

	/**
	 * {@code Referrer-Policy}, bo adres strony potwierdzenia niesie token, a link
	 * „wróć" prowadzi dalej — przeglądarka nie może przekazać tego adresu następnej
	 * stronie.
	 */
	private static ResponseEntity<String> html(HttpStatus status, String body) {
		return ResponseEntity.status(status).contentType(HTML_UTF8)
				.header("Content-Security-Policy", CONTENT_SECURITY_POLICY).header("Referrer-Policy", "no-referrer")
				.body(body);
	}
}
