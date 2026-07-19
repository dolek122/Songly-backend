package com.songly.notification;

import com.songly.notification.dto.NotificationResponse;
import com.songly.notification.dto.UnreadCountResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@SecurityRequirement(name = "bearerAuth")
class NotificationController {

	private final NotificationService notificationService;

	NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
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

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
