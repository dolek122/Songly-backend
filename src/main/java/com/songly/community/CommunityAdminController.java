package com.songly.community;

import com.songly.community.dto.EscalatedAppealResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Druga instancja odwoławcza od banów w społecznościach (ADR 0028).
 *
 * <p>
 * Kontroler siedzi w module community, bo to jego dane — a leży pod prefiksem
 * {@code /api/v1/admin/**}, więc rolę pilnuje {@code SecurityConfig} (ADR 0021)
 * i nie trzeba jej tu powtarzać. Ten sam układ co przy historii moderacji (ADR
 * 0024).
 */
@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
class CommunityAdminController {

	private final CommunityService communityService;

	CommunityAdminController(CommunityService communityService) {
		this.communityService = communityService;
	}

	@GetMapping("/ban-appeals")
	List<EscalatedAppealResponse> escalatedAppeals() {
		return communityService.escalatedAppeals();
	}

	/** Uchylenie banu = uwzględnienie odwołania w drugiej instancji. */
	@DeleteMapping("/communities/{communityId}/bans/{userId}")
	ResponseEntity<Void> overturnBan(Authentication authentication, @PathVariable UUID communityId,
			@PathVariable UUID userId) {
		communityService.overturnBan(communityId, userId, (UUID) authentication.getPrincipal());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/communities/{communityId}/bans/{userId}/escalation-rejection")
	ResponseEntity<Void> rejectEscalation(Authentication authentication, @PathVariable UUID communityId,
			@PathVariable UUID userId) {
		communityService.rejectBanAppealEscalation(communityId, userId, (UUID) authentication.getPrincipal());
		return ResponseEntity.noContent().build();
	}
}
