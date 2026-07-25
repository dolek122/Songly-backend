package com.songly.nowplaying;

import com.songly.nowplaying.dto.ConnectResponse;
import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/now-playing")
class NowPlayingController {

	private final NowPlayingService nowPlayingService;
	private final NowPlayingStreamHub streamHub;

	NowPlayingController(NowPlayingService nowPlayingService, NowPlayingStreamHub streamHub) {
		this.nowPlayingService = nowPlayingService;
		this.streamHub = streamHub;
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
	 * "Co słuchają znajomi teraz" (ADR 0007, Etap 0) — statusy znajomych z cache'a,
	 * bez odpytywania providera per znajomy. Zwraca tylko tych, którzy mają
	 * aktualny status; pusta lista, gdy nikt nie gra albo brak znajomych.
	 */
	@GetMapping("/friends")
	@SecurityRequirement(name = "bearerAuth")
	List<FriendNowPlayingResponse> friendsNowPlaying(Authentication authentication) {
		return nowPlayingService.friendsNowPlaying(currentUserId(authentication));
	}

	/**
	 * Strumień SSE ze statusami znajomych na żywo (ADR 0007, Etap 1). Klient trzyma
	 * jedno połączenie; serwer pushuje zdarzenie {@code now-playing}
	 * ({@link FriendNowPlayingResponse}), gdy status któregoś znajomego się zmieni.
	 * Stan początkowy klient pobiera przez {@code GET /friends}.
	 */
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@SecurityRequirement(name = "bearerAuth")
	SseEmitter stream(Authentication authentication) {
		return streamHub.subscribe(currentUserId(authentication));
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
