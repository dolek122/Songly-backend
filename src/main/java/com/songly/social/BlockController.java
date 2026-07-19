package com.songly.social;

import com.songly.social.dto.BlockedUserResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/blocks")
@SecurityRequirement(name = "bearerAuth")
class BlockController {

	private final SocialGraphService socialGraphService;

	BlockController(SocialGraphService socialGraphService) {
		this.socialGraphService = socialGraphService;
	}

	@PutMapping("/{userId}")
	ResponseEntity<Void> blockUser(Authentication authentication, @PathVariable UUID userId) {
		socialGraphService.blockUser(currentUserId(authentication), userId);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{userId}")
	ResponseEntity<Void> unblockUser(Authentication authentication, @PathVariable UUID userId) {
		socialGraphService.unblockUser(currentUserId(authentication), userId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping
	Page<BlockedUserResponse> listBlockedUsers(Authentication authentication,
			@PageableDefault(size = 20) Pageable pageable) {
		return socialGraphService.listBlockedUsers(currentUserId(authentication), pageable);
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
