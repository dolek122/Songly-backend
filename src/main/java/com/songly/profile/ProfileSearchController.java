package com.songly.profile;

import com.songly.profile.dto.UserSearchResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wyszukiwarka użytkowników po nazwie wyświetlanej — osobny kontroler, bo
 * {@link ProfileController} jest przypięty do {@code /me}. Umożliwia
 * znajdowanie osób do dodania do znajomych (moduł social-graph wysyła
 * zaproszenie po {@code addresseeId}, którego wcześniej nie było jak zdobyć bez
 * wyszukiwania).
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

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
