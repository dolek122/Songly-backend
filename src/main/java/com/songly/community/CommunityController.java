package com.songly.community;

import com.songly.community.dto.CommunityMemberResponse;
import com.songly.community.dto.CommunityResponse;
import com.songly.community.dto.CreateCommunityRequest;
import com.songly.community.dto.UpdateCommunityRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/communities")
@SecurityRequirement(name = "bearerAuth")
class CommunityController {

	private final CommunityService communityService;

	CommunityController(CommunityService communityService) {
		this.communityService = communityService;
	}

	@PostMapping
	ResponseEntity<CommunityResponse> create(Authentication authentication,
			@Valid @RequestBody CreateCommunityRequest request) {
		CommunityResponse response = communityService.create(currentUserId(authentication), request.name(),
				request.description());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	Page<CommunityResponse> list(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return communityService.list(currentUserId(authentication), pageable);
	}

	@GetMapping("/mine")
	Page<CommunityResponse> listMine(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return communityService.listMine(currentUserId(authentication), pageable);
	}

	@GetMapping("/{communityId}")
	CommunityResponse get(Authentication authentication, @PathVariable UUID communityId) {
		return communityService.get(communityId, currentUserId(authentication));
	}

	@PutMapping("/{communityId}")
	CommunityResponse update(Authentication authentication, @PathVariable UUID communityId,
			@Valid @RequestBody UpdateCommunityRequest request) {
		return communityService.update(communityId, currentUserId(authentication), request.name(),
				request.description());
	}

	@DeleteMapping("/{communityId}")
	ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID communityId) {
		communityService.delete(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{communityId}/membership")
	ResponseEntity<Void> join(Authentication authentication, @PathVariable UUID communityId) {
		communityService.join(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{communityId}/membership")
	ResponseEntity<Void> leave(Authentication authentication, @PathVariable UUID communityId) {
		communityService.leave(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{communityId}/members")
	Page<CommunityMemberResponse> listMembers(@PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return communityService.listMembers(communityId, pageable);
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
