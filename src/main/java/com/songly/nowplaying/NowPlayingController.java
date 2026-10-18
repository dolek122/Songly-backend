package com.songly.nowplaying;

import com.songly.nowplaying.dto.ConnectResponse;
import com.songly.nowplaying.dto.FriendNowPlayingResponse;
import com.songly.nowplaying.dto.ManualNowPlayingRequest;
import com.songly.nowplaying.dto.NowPlayingSettingsResponse;
import com.songly.nowplaying.dto.NowPlayingStatusResponse;
import com.songly.nowplaying.dto.UpdateNowPlayingSettingsRequest;
import com.songly.common.web.ApiError;
import com.songly.common.web.ApiException;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/now-playing")
class NowPlayingController {

	/**
	 * Kod z {@link SpotifyApiException} — provider wrócił z błędem bez kodu
	 * autoryzacyjnego.
	 */
	private static final String MUSIC_PROVIDER_ERROR = "MUSIC_PROVIDER_ERROR";

	private final NowPlayingService nowPlayingService;
	private final NowPlayingStreamHub streamHub;
	private final NowPlayingProperties properties;

	NowPlayingController(NowPlayingService nowPlayingService, NowPlayingStreamHub streamHub,
			NowPlayingProperties properties) {
		this.nowPlayingService = nowPlayingService;
		this.streamHub = streamHub;
		this.properties = properties;
	}

	@PostMapping("/spotify/connect")
	@SecurityRequirement(name = "bearerAuth")
	ConnectResponse connect(Authentication authentication) {
		return new ConnectResponse(nowPlayingService.beginConnect(currentUserId(authentication)));
	}

	/**
	 * Przeglądarka wraca tu z redirectu Spotify bez JWT — endpoint jest publiczny,
	 * a użytkownika identyfikuje jednorazowy state z Redis.
	 *
	 * <p>
	 * Każdy wynik, także błąd, kończy się przekierowaniem do aplikacji (ADR 0038):
	 * po drugiej stronie stoi przeglądarka, a nie klient API, więc JSON z błędem
	 * byłby dla użytkownika ślepą uliczką, a 204 — pustą kartą. Jedyny wyjątek to
	 * brak {@code state}: wtedy nie jest to powrót od providera.
	 */
	// springdoc nie dokłada adnotowanych odpowiedzi do wywnioskowanych, tylko je
	// nimi zastępuje — więc gdy opisujemy błędy, sukces trzeba wypisać razem z
	// nimi.
	@GetMapping("/spotify/callback")
	@ApiResponse(responseCode = "302", description = "Przekierowanie do aplikacji z wynikiem w parametrze `result`: "
			+ "`connected`, `denied` (użytkownik odmówił zgody) albo `error` z kodem błędu w `code` "
			+ "(`INVALID_OAUTH_STATE`, `INVALID_AUTHORIZATION_CODE`, `MUSIC_PROVIDER_ERROR`)", content = @Content)
	@ApiResponse(responseCode = "400", description = "Brak parametru `state` (`MISSING_PARAMETER`)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
	ResponseEntity<Void> callback(@RequestParam String state, @RequestParam(required = false) String code,
			@RequestParam(required = false) String error) {
		if (code == null) {
			nowPlayingService.abandonConnect(state);
			return "access_denied".equals(error) ? backToApp("denied", null) : backToApp("error", MUSIC_PROVIDER_ERROR);
		}
		try {
			nowPlayingService.completeConnect(state, code);
		} catch (ApiException e) {
			return backToApp("error", e.getCode());
		}
		return backToApp("connected", null);
	}

	private ResponseEntity<Void> backToApp(String result, String errorCode) {
		URI location = UriComponentsBuilder.fromUriString(properties.getConnectResultUrl()).queryParam("result", result)
				.queryParamIfPresent("code", Optional.ofNullable(errorCode)).build().toUri();
		return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
	}

	@DeleteMapping("/spotify")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> disconnect(Authentication authentication) {
		nowPlayingService.disconnect(currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Jedyny endpoint, który może odpytać providera na żywo (gdy nie ma nic w
	 * cache'u), więc jako jedyny wystawia klientowi jego awarie.
	 */
	@GetMapping("/me")
	@SecurityRequirement(name = "bearerAuth")
	@ApiResponse(responseCode = "200", description = "Aktualny status", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NowPlayingStatusResponse.class)))
	@ApiResponse(responseCode = "204", description = "Nic aktualnie nie gra", content = @Content)
	@ApiResponse(responseCode = "409", description = "Utracono dostęp do serwisu muzycznego — połączenie zostało "
			+ "usunięte, trzeba przejść OAuth ponownie (`PROVIDER_REAUTHORIZATION_REQUIRED`)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
	@ApiResponse(responseCode = "502", description = "Serwis muzyczny nie odpowiedział poprawnie (`MUSIC_PROVIDER_ERROR`)", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
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
	 *
	 * <p>
	 * {@code status == null} w zdarzeniu znaczy "przestał słuchać" — znajomego
	 * należy wtedy zdjąć z listy grających. Serwer wysyła też co kilkanaście sekund
	 * komentarz SSE (linia zaczynająca się od {@code :}), który klient ignoruje;
	 * jego brak przez dłuższy czas to sygnał do reconnectu.
	 *
	 * <p>
	 * Liczba równoległych strumieni na użytkownika jest limitowana — nadmiarowe
	 * połączenie dostaje 429 {@code TOO_MANY_STREAMS}.
	 */
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@SecurityRequirement(name = "bearerAuth")
	// Bez tego springdoc opisuje ten endpoint typem zwracanym metody (SseEmitter),
	// co klientowi nie mówi nic o treści zdarzeń — a to jedyna ścieżka, której
	// kształtu nie da się wyczytać z kontraktu w żaden inny sposób.
	@ApiResponse(responseCode = "200", description = "Strumień zdarzeń `now-playing`. "
			+ "Każde zdarzenie niesie jeden obiekt; `status: null` znaczy, że znajomy przestał słuchać.", content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(implementation = FriendNowPlayingResponse.class)))
	@ApiResponse(responseCode = "429", description = "Przekroczony limit równoległych strumieni na użytkownika", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
	SseEmitter stream(Authentication authentication) {
		return streamHub.subscribe(currentUserId(authentication));
	}

	/**
	 * Widoczność statusu dla znajomych (ADR 0007, Etap 4). Domyślnie włączona —
	 * status i tak pojawia się dopiero, gdy użytkownik sam podłączy serwis muzyczny
	 * albo ustawi status ręcznie.
	 */
	@GetMapping("/settings")
	@SecurityRequirement(name = "bearerAuth")
	NowPlayingSettingsResponse settings(Authentication authentication) {
		return nowPlayingService.getSettings(currentUserId(authentication));
	}

	@PutMapping("/settings")
	@SecurityRequirement(name = "bearerAuth")
	ResponseEntity<Void> updateSettings(Authentication authentication,
			@Valid @RequestBody UpdateNowPlayingSettingsRequest request) {
		nowPlayingService.updateSettings(currentUserId(authentication), request);
		return ResponseEntity.noContent().build();
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
