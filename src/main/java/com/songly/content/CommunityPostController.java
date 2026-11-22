package com.songly.content;

import com.songly.content.dto.CreatePostRequest;
import com.songly.content.dto.PostResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/communities/{communityId}/posts")
@SecurityRequirement(name = "bearerAuth")
class CommunityPostController {

	private final ContentService contentService;

	CommunityPostController(ContentService contentService) {
		this.contentService = contentService;
	}

	@PostMapping
	ResponseEntity<PostResponse> createCommunityPost(Authentication authentication, @PathVariable UUID communityId,
			@Valid @RequestBody CreatePostRequest request) {
		PostResponse response = contentService.createPost(currentUserId(authentication), request.content(),
				request.imageKey(), communityId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	Page<PostResponse> listCommunityPosts(Authentication authentication, @PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return contentService.listCommunityPosts(communityId, currentUserId(authentication), pageable);
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
