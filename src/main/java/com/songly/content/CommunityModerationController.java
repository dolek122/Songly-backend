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

/**
 * Kolejka moderacji jednej społeczności.
 *
 * <p>
 * Kontroler leży w module content, mimo że ścieżka zaczyna się od
 * {@code /communities} — bo zgłoszenia dotyczą postów, a te należą do content
 * (Claude.md §2). Ta sama zasada, co przy {@code CommunityPostController}.
 *
 * <p>
 * Ścieżka to {@code /post-reports}, a nie {@code /posts/reports}: to drugie
 * kolidowałoby wzorcem z {@code /posts/{postId}} i rozstrzygałoby się regułami
 * pierwszeństwa Springa zamiast wprost.
 */
@RestController
@RequestMapping("/api/v1/communities/{communityId}/post-reports")
@SecurityRequirement(name = "bearerAuth")
class CommunityModerationController {

	private final ContentService contentService;

	CommunityModerationController(ContentService contentService) {
		this.contentService = contentService;
	}

	/** Najstarsze pierwsze — kolejka, nie tablica ogłoszeń. */
	@GetMapping
	Page<PostReportResponse> listPendingReports(Authentication authentication, @PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return contentService.listPendingReports(communityId, currentUserId(authentication), pageable);
	}

	/**
	 * Odrzuca zgłoszenie. Odpowiednika „uwzględnij" nie ma celowo: uwzględnienie
	 * <i>jest</i> skasowaniem posta ({@code DELETE /api/v1/posts/{postId}}), które
	 * moderator już umie i które zabiera zgłoszenia razem z postem.
	 */
	@PostMapping("/{reportId}/dismissal")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void dismissReport(Authentication authentication, @PathVariable UUID communityId, @PathVariable UUID reportId) {
		contentService.dismissReport(communityId, reportId, currentUserId(authentication));
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
