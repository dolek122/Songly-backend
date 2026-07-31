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

/**
 * Posty w społecznościach. Ścieżka należy do społeczności, ale zasób to post,
 * więc kontroler siedzi w module content — moduł community nie wie nic o
 * postach.
 *
 * <p>
 * Reszta operacji na takim poście (lajki, komentarze, udostępnienia, usuwanie)
 * idzie zwykłymi endpointami {@code /api/v1/posts/{postId}} — post w
 * społeczności to ten sam byt, tylko opublikowany w innym miejscu.
 */
@RestController
@RequestMapping("/api/v1/communities/{communityId}/posts")
@SecurityRequirement(name = "bearerAuth")
class CommunityPostController {

	private final ContentService contentService;

	CommunityPostController(ContentService contentService) {
		this.contentService = contentService;
	}

	// Nazwy metod celowo różne od tych w PostController: springdoc bierze z nich
	// operationId, a przy kolizji dokleja numer ("listPosts_1"), co w generowanym
	// kliencie nie znaczy nic.
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
