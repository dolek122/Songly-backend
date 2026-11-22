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

@Hidden
@Controller
class AccountDeletionPageController {

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

	@PostMapping(path = AccountDeletionPages.START_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
	ResponseEntity<String> requestLink(@Valid @ModelAttribute AccountDeletionLinkRequest form, BindingResult binding) {
		if (binding.hasErrors()) {
			return html(HttpStatus.BAD_REQUEST,
					AccountDeletionPages.start(ttlMinutes(), "Podaj poprawny adres e-mail."));
		}
		accountDeletionService.requestDeletion(form.email());
		return html(HttpStatus.OK, AccountDeletionPages.linkSent(ttlMinutes()));
	}

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

	private static ResponseEntity<String> html(HttpStatus status, String body) {
		return ResponseEntity.status(status).contentType(HTML_UTF8)
				.header("Content-Security-Policy", CONTENT_SECURITY_POLICY).header("Referrer-Policy", "no-referrer")
				.body(body);
	}
}
