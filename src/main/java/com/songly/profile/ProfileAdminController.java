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
