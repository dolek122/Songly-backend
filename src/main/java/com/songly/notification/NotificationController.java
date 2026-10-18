package com.songly.notification;

import com.songly.notification.dto.MailPreferencesResponse;
import com.songly.notification.dto.NotificationResponse;
import com.songly.notification.dto.RegisterPushDeviceRequest;
import com.songly.notification.dto.RemovePushDeviceRequest;
import com.songly.notification.dto.UnreadCountResponse;
import com.songly.notification.dto.UpdateMailPreferencesRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {

	private final NotificationService notificationService;
	private final NotificationMailPreferenceService mailPreferenceService;
	private final PushDeviceService pushDeviceService;

	NotificationController(NotificationService notificationService,
			NotificationMailPreferenceService mailPreferenceService, PushDeviceService pushDeviceService) {
		this.notificationService = notificationService;
		this.mailPreferenceService = mailPreferenceService;
		this.pushDeviceService = pushDeviceService;
	}

	@GetMapping
	Page<NotificationResponse> list(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return notificationService.list(currentUserId(authentication), pageable);
	}

	@GetMapping("/unread-count")
	UnreadCountResponse unreadCount(Authentication authentication) {
		return new UnreadCountResponse(notificationService.unreadCount(currentUserId(authentication)));
	}

	@PostMapping("/{notificationId}/read")
	ResponseEntity<Void> markRead(Authentication authentication, @PathVariable UUID notificationId) {
		notificationService.markRead(notificationId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/read-all")
	ResponseEntity<Void> markAllRead(Authentication authentication) {
		notificationService.markAllRead(currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Preferencje dotyczą <b>wyłącznie poczty</b> (ADR 0020) — stąd
	 * {@code mail-preferences}, a nie {@code preferences}. Skrzynki w aplikacji nie
	 * da się wyłączyć i nazwa endpointu ma to mówić wprost.
	 */
	@GetMapping("/mail-preferences")
	MailPreferencesResponse mailPreferences(Authentication authentication) {
		return mailPreferenceService.settings(currentUserId(authentication));
	}

	@PutMapping("/mail-preferences")
	ResponseEntity<Void> updateMailPreferences(Authentication authentication,
			@Valid @RequestBody UpdateMailPreferencesRequest request) {
		mailPreferenceService.update(currentUserId(authentication), request.decisionsAboutMe(),
				request.moderationQueue());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Rejestracja urządzenia push (ADR 0040). Idempotentna — klient woła ją przy
	 * każdym starcie i przy każdej wymianie tokenu przez FCM.
	 */
	@PutMapping("/push-devices")
	ResponseEntity<Void> registerPushDevice(Authentication authentication,
			@Valid @RequestBody RegisterPushDeviceRequest request) {
		pushDeviceService.register(currentUserId(authentication), request.token(), request.platform());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Wyrejestrowanie przed wylogowaniem. {@code POST .../removal}, nie
	 * {@code DELETE} — token jedzie w ciele (patrz
	 * {@link RemovePushDeviceRequest}), a {@code DELETE} z ciałem bywa gubiony
	 * przez proxy. Cudzy albo nieznany token też daje 204.
	 */
	@PostMapping("/push-devices/removal")
	ResponseEntity<Void> removePushDevice(Authentication authentication,
			@Valid @RequestBody RemovePushDeviceRequest request) {
		pushDeviceService.unregister(currentUserId(authentication), request.token());
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
