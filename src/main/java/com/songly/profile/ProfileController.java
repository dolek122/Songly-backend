package com.songly.profile;

import com.songly.profile.dto.GenreResponse;
import com.songly.profile.dto.ProfileResponse;
import com.songly.profile.dto.SetFavoriteGenresRequest;
import com.songly.profile.dto.UpdateProfileRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profiles/me")
@SecurityRequirement(name = "bearerAuth")
class ProfileController {

	private final ProfileService profileService;

	ProfileController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@GetMapping
	ProfileResponse getMyProfile(Authentication authentication) {
		return profileService.getProfile(currentUserId(authentication));
	}

	@PutMapping
	ProfileResponse updateMyProfile(Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
		return profileService.updateProfile(currentUserId(authentication), request);
	}

	@GetMapping("/genres")
	List<GenreResponse> getMyFavoriteGenres(Authentication authentication) {
		return profileService.getFavoriteGenres(currentUserId(authentication));
	}

	@PutMapping("/genres")
	ResponseEntity<Void> setMyFavoriteGenres(Authentication authentication,
			@Valid @RequestBody SetFavoriteGenresRequest request) {
		profileService.setFavoriteGenres(currentUserId(authentication), request.genreIds());
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
