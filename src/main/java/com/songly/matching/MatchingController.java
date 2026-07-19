package com.songly.matching;

import com.songly.matching.dto.MatchResponse;
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
@RequestMapping("/api/v1/matches")
@SecurityRequirement(name = "bearerAuth")
class MatchingController {

	private final MatchingService matchingService;

	MatchingController(MatchingService matchingService) {
		this.matchingService = matchingService;
	}

	@GetMapping
	Page<MatchResponse> matches(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return matchingService.matches((UUID) authentication.getPrincipal(), pageable);
	}
}
