package com.songly.nowplaying;

import com.songly.nowplaying.dto.ConnectResponse;
import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/now-playing")
class NowPlayingController {

	private final NowPlayingService nowPlayingService;

	NowPlayingController(NowPlayingService nowPlayingService) {
		this.nowPlayingService = nowPlayingService;
	}

	@PostMapping("/spotify/connect")
	@SecurityRequirement(name = "bearerAuth")
	ConnectResponse connect(Authentication authentication) {
		return new ConnectResponse(nowPlayingService.beginConnect(currentUserId(authentication)));
	}

	/**
	 * Przeglądarka wraca tu z redirectu Spotify bez JWT — endpoint jest publiczny,
	 * a użytkownika identyfikuje jednorazowy state z Redis.
	 */
	@GetMapping("/spotify/callback")
	ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
		nowPlayingService.completeConnect(state, code);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/spotify")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> disconnect(Authentication authentication) {
		nowPlayingService.disconnect(currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<NowPlayingStatusResponse> myStatus(Authentication authentication) {
		return nowPlayingService.getStatus(currentUserId(authentication)).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/**
	 * Uniwersalny fallback dla serwisów bez integracji (Apple Music, Tidal, YouTube
	 * Music...) — Claude.md §7. Ma pierwszeństwo przed providerem, dopóki nie
	 * wygaśnie albo user go nie wyczyści.
	 */
	@PutMapping("/manual")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> setManualStatus(Authentication authentication,
			@Valid @RequestBody ManualNowPlayingRequest request) {
		nowPlayingService.setManualStatus(currentUserId(authentication), request);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/manual")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> clearManualStatus(Authentication authentication) {
		nowPlayingService.clearManualStatus(currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
