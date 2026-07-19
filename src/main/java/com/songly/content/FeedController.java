package com.songly.content;

import com.songly.content.dto.PostResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/feed")
@SecurityRequirement(name = "bearerAuth")
class FeedController {

	private final ContentService contentService;

	FeedController(ContentService contentService) {
		this.contentService = contentService;
	}

	@GetMapping
	Page<PostResponse> feed(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return contentService.feed((UUID) authentication.getPrincipal(), pageable);
	}
}
