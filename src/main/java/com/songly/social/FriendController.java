package com.songly.social;

import com.songly.social.dto.FriendRequestDirection;
import com.songly.social.dto.FriendRequestResponse;
import com.songly.social.dto.FriendResponse;
import com.songly.social.dto.SendFriendRequestRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/friends")
@SecurityRequirement(name = "bearerAuth")
class FriendController {

	private final SocialGraphService socialGraphService;

	FriendController(SocialGraphService socialGraphService) {
		this.socialGraphService = socialGraphService;
	}

	@PostMapping("/requests")
	ResponseEntity<FriendRequestResponse> sendRequest(Authentication authentication,
			@Valid @RequestBody SendFriendRequestRequest request) {
		FriendRequestResponse response = socialGraphService.sendRequest(currentUserId(authentication),
				request.addresseeId());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/requests")
	Page<FriendRequestResponse> listRequests(Authentication authentication,
			@RequestParam(defaultValue = "INCOMING") FriendRequestDirection direction,
			@PageableDefault(size = 20) Pageable pageable) {
		return socialGraphService.listRequests(currentUserId(authentication), direction, pageable);
	}

	@PostMapping("/requests/{requestId}/accept")
	FriendRequestResponse acceptRequest(Authentication authentication, @PathVariable UUID requestId) {
		return socialGraphService.acceptRequest(requestId, currentUserId(authentication));
	}

	@PostMapping("/requests/{requestId}/decline")
	FriendRequestResponse declineRequest(Authentication authentication, @PathVariable UUID requestId) {
		return socialGraphService.declineRequest(requestId, currentUserId(authentication));
	}

	@DeleteMapping("/requests/{requestId}")
	ResponseEntity<Void> cancelRequest(Authentication authentication, @PathVariable UUID requestId) {
		socialGraphService.cancelRequest(requestId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@GetMapping
	Page<FriendResponse> listFriends(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return socialGraphService.listFriends(currentUserId(authentication), pageable);
	}

	@DeleteMapping("/{userId}")
	ResponseEntity<Void> removeFriend(Authentication authentication, @PathVariable UUID userId) {
		socialGraphService.removeFriend(currentUserId(authentication), userId);
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
