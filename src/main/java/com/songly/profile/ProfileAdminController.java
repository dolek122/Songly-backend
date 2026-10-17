package com.songly.profile;

import com.songly.profile.dto.ProfileSummaryResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Nazwy użytkowników dla administracji (ADR 0036), także zawieszonych.
 *
 * <p>
 * Wykaz zawieszeń, kolejka eskalacji i lista adminów niosą same identyfikatory,
 * bo żyją w {@code identity} i {@code community}, a {@code identity} nie może
 * zależeć od {@code profile} (cykl, reguła 2 {@code ModuleBoundariesTest}).
 * Dociąganie nazw przez {@code GET /profiles/{id}} nie działało dla
 * zawieszonych, bo ich profil jest ukryty (404, ADR 0026) — czyli właśnie dla
 * osób, o których administracja decyduje.
 *
 * <p>
 * Pod prefiksem {@code /api/v1/admin/**}, więc rolę pilnuje
 * {@code SecurityConfig} (ADR 0021) — ten sam układ co
 * {@code CommunityAdminController}.
 */
@RestController
@RequestMapping("/api/v1/admin/profiles")
@SecurityRequirement(name = "bearerAuth")
class ProfileAdminController {

	private final ProfileService profileService;

	ProfileAdminController(ProfileService profileService) {
		this.profileService = profileService;
	}

	@GetMapping
	List<ProfileSummaryResponse> profileSummaries(@RequestParam Set<UUID> userIds) {
		return profileService.profileSummaries(userIds);
	}
}
