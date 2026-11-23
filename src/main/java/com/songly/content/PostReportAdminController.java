package com.songly.content;

import com.songly.content.dto.PostReportResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
class PostReportAdminController {

	private final ContentService contentService;

	PostReportAdminController(ContentService contentService) {
		this.contentService = contentService;
	}

	@GetMapping("/post-reports")
	Page<PostReportResponse> listPendingReports(Authentication authentication,
			@PageableDefault(size = 20) Pageable pageable) {
		return contentService.listPendingPlatformReports(currentUserId(authentication), pageable);
	}

	@PostMapping("/post-reports/{reportId}/dismissal")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void dismissReport(Authentication authentication, @PathVariable UUID reportId) {
		contentService.dismissPlatformReport(reportId, currentUserId(authentication));
	}

	@DeleteMapping("/posts/{postId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removePost(Authentication authentication, @PathVariable UUID postId) {
		contentService.removePlatformPost(postId, currentUserId(authentication));
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
