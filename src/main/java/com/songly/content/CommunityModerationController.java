package com.songly.content;

import com.songly.content.dto.PostReportResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/communities/{communityId}/post-reports")
@SecurityRequirement(name = "bearerAuth")
class CommunityModerationController {

	private final ContentService contentService;

	CommunityModerationController(ContentService contentService) {
		this.contentService = contentService;
	}

	@GetMapping
	Page<PostReportResponse> listPendingReports(Authentication authentication, @PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return contentService.listPendingReports(communityId, currentUserId(authentication), pageable);
	}

	@PostMapping("/{reportId}/dismissal")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void dismissReport(Authentication authentication, @PathVariable UUID communityId, @PathVariable UUID reportId) {
		contentService.dismissReport(communityId, reportId, currentUserId(authentication));
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
