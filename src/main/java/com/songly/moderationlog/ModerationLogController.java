package com.songly.moderationlog;

import com.songly.moderationlog.dto.ModerationLogEntryResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dwa odczyty historii moderacji (ADR 0024), bo są dwie różne publiczności.
 *
 * <p>
 * Ścieżka administracyjna leży pod {@code /api/v1/admin/**}, więc rolę pilnuje
 * {@code SecurityConfig} (ADR 0021) — kontroler nie powtarza tego sprawdzenia.
 * Ścieżkę społecznościową zabezpiecza moduł community przez swój publiczny
 * interfejs, bo tylko on wie, kto moderuje.
 */
@RestController
@SecurityRequirement(name = "bearerAuth")
class ModerationLogController {

	private final ModerationLogService moderationLogService;

	ModerationLogController(ModerationLogService moderationLogService) {
		this.moderationLogService = moderationLogService;
	}

	@GetMapping("/api/v1/communities/{communityId}/moderation-log")
	Page<ModerationLogEntryResponse> communityLog(Authentication authentication, @PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return moderationLogService.communityLog(communityId, (UUID) authentication.getPrincipal(), pageable);
	}

	/**
	 * {@code userId} opcjonalny — bez niego cała platforma, z nim historia jednej
	 * osoby.
	 */
	@GetMapping("/api/v1/admin/moderation-log")
	Page<ModerationLogEntryResponse> platformLog(@RequestParam(required = false) UUID userId,
			@PageableDefault(size = 20) Pageable pageable) {
		return moderationLogService.platformLog(userId, pageable);
	}
}
