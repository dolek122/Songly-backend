package com.songly.profile;

import com.songly.profile.dto.GenreResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/genres")
@SecurityRequirement(name = "bearerAuth")
class GenreController {

	private final ProfileService profileService;

	GenreController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@GetMapping
	Page<GenreResponse> listGenres(@PageableDefault(size = 20) Pageable pageable) {
		return profileService.listGenres(pageable);
	}
}
