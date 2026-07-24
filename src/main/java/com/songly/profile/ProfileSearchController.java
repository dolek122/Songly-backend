package com.songly.profile;

import com.songly.profile.dto.PublicProfileResponse;
import com.songly.profile.dto.UserSearchResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publiczny dostęp do cudzych profili — osobny kontroler, bo
 * {@link ProfileController} jest przypięty do {@code /me}. Dwie ścieżki:
 * wyszukiwanie po nazwie ({@code /search}, do dodawania znajomych — moduł
 * social-graph wysyła zaproszenie po {@code addresseeId}, którego inaczej nie
 * było jak zdobyć) oraz pojedynczy profil po {@code userId} (do oglądania
 * cudzego profilu). Literały {@code /search} i {@code /me} mają w dopasowaniu
 * ścieżki pierwszeństwo nad zmienną {@code /{userId}}, więc nie kolidują.
 */
@RestController
@RequestMapping("/api/v1/profiles")
@SecurityRequirement(name = "bearerAuth")
class ProfileSearchController {

	private final ProfileService profileService;

	ProfileSearchController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@GetMapping("/search")
	Page<UserSearchResponse> searchUsers(Authentication authentication, @RequestParam String query,
			@PageableDefault(size = 20) Pageable pageable) {
		return profileService.searchUsers(currentUserId(authentication), query, pageable);
	}

	@GetMapping("/{userId}")
	PublicProfileResponse getPublicProfile(@PathVariable UUID userId) {
		return profileService.getPublicProfile(userId);
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
