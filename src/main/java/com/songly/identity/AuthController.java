package com.songly.identity;

import com.songly.identity.dto.AuthTokensResponse;
import com.songly.identity.dto.ChangePasswordRequest;
import com.songly.identity.dto.DeleteAccountRequest;
import com.songly.identity.dto.LoginRequest;
import com.songly.identity.dto.PasswordResetConfirmRequest;
import com.songly.identity.dto.PasswordResetRequest;
import com.songly.identity.dto.RefreshRequest;
import com.songly.identity.dto.RegisterRequest;
import com.songly.identity.dto.SuspensionAppealRequest;
import com.songly.identity.dto.SuspensionResponse;
import com.songly.identity.dto.VerifyEmailRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final AuthService authService;
	private final RegistrationService registrationService;
	private final PasswordResetService passwordResetService;
	private final AccountSuspensionService suspensionService;

	AuthController(AuthService authService, RegistrationService registrationService,
			PasswordResetService passwordResetService, AccountSuspensionService suspensionService) {
		this.authService = authService;
		this.registrationService = registrationService;
		this.passwordResetService = passwordResetService;
		this.suspensionService = suspensionService;
	}

	/**
	 * Przyjmuje zgłoszenie rejestracji. <b>202 zawsze</b> — także wtedy, gdy pod
	 * tym adresem konto już istnieje. Wcześniejsze 409 {@code EMAIL_ALREADY_USED}
	 * było wyrocznią: wystarczyło wpisywać kolejne adresy, żeby wylistować, kto ma
	 * tu konto.
	 *
	 * <p>
	 * Odpowiedź celowo <b>nie</b> zawiera tokenów — konto jeszcze nie istnieje.
	 * Powstaje dopiero przy potwierdzeniu adresu, i to tam wydajemy sesję.
	 */
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void register(@Valid @RequestBody RegisterRequest request) {
		registrationService.register(request);
	}

	/**
	 * Potwierdza adres i zakłada konto, od razu wydając parę tokenów. Kliknięcie w
	 * link dowodzi kontroli nad skrzynką, a hasło podał ten sam człowiek chwilę
	 * wcześniej — osobne logowanie zaraz po potwierdzeniu nie sprawdzałoby niczego
	 * więcej.
	 */
	@PostMapping("/register/verify")
	AuthTokensResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
		return registrationService.verify(request.token());
	}

	@PostMapping("/login")
	AuthTokensResponse login(@Valid @RequestBody LoginRequest request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	AuthTokensResponse refresh(@Valid @RequestBody RefreshRequest request) {
		return authService.refresh(request);
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
		authService.logout(request);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Odpowiedź jest pusta celowo — nie wydajemy nowej pary tokenów. Skoro zmiana
	 * hasła odwołuje wszystkie sesje, wydanie tu świeżej sesji po cichu cofałoby
	 * połowę tego skutku dla urządzenia, które akurat wysłało żądanie.
	 */
	@PutMapping("/password")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> changePassword(Authentication authentication,
			@Valid @RequestBody ChangePasswordRequest request) {
		authService.changePassword((UUID) authentication.getPrincipal(), request.currentPassword(),
				request.newPassword());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Zamawia link resetujący hasło. <b>202 zawsze</b> — także dla adresu, pod
	 * którym nie ma konta, i dla żądania wyciszonego przez odstęp między wysyłkami.
	 * Endpoint jest publiczny, więc każde rozróżnienie w odpowiedzi byłoby gotową
	 * listą kont (Claude.md §13). „Accepted", a nie „No content", mówi zresztą
	 * prawdę o tym, co się stało: przyjęliśmy żądanie, a wysyłka dzieje się poza
	 * cyklem tej odpowiedzi.
	 */
	@PostMapping("/password/reset-request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
		passwordResetService.requestReset(request.email());
	}

	/**
	 * Podmienia hasło na podstawie tokenu z linku. Podobnie jak zmiana hasła nie
	 * wydaje tokenów: reset unieważnia wszystkie sesje, więc oddanie tu świeżej
	 * pary cofałoby połowę tego skutku. Użytkownik loguje się nowym hasłem.
	 */
	@PostMapping("/password/reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@Valid @RequestBody PasswordResetConfirmRequest request) {
		passwordResetService.resetPassword(request.token(), request.newPassword());
	}

	@DeleteMapping("/me")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> deleteAccount(Authentication authentication,
			@Valid @RequestBody DeleteAccountRequest request) {
		authService.deleteAccount((UUID) authentication.getPrincipal(), request.password());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Własny stan zawieszenia (ADR 0022) — jeden z nielicznych endpointów
	 * dostępnych zawieszonemu koncie, bo inaczej nie miałoby jak poznać powodu ani
	 * terminu. Konto aktywne dostaje {@code suspended=false}, nie 404.
	 */
	@GetMapping("/me/suspension")
	@SecurityRequirement(name = "bearerAuth")
	SuspensionResponse suspension(Authentication authentication) {
		return suspensionService.ownSuspension((UUID) authentication.getPrincipal());
	}

	/**
	 * Odwołanie od własnego zawieszenia (ADR 0023) — drugi, obok odczytu statusu,
	 * endpoint dostępny zawieszonemu koncie. Treść czyta wyłącznie administracja, w
	 * wykazie zawieszeń.
	 */
	@PostMapping("/me/suspension/appeal")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> appealSuspension(Authentication authentication,
			@Valid @RequestBody SuspensionAppealRequest request) {
		suspensionService.appeal((UUID) authentication.getPrincipal(), request.text());
		return ResponseEntity.noContent().build();
	}
}
