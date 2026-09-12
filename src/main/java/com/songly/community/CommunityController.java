package com.songly.community;

import com.songly.community.dto.BanAppealRequest;
import com.songly.community.dto.BanMemberRequest;
import com.songly.community.dto.CommunityBanResponse;
import com.songly.community.dto.CommunityMemberResponse;
import com.songly.community.dto.CommunityResponse;
import com.songly.community.dto.CreateCommunityRequest;
import com.songly.community.dto.MembershipStatusResponse;
import com.songly.community.dto.SetMemberRoleRequest;
import com.songly.community.dto.UpdateCommunityRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/communities")
@SecurityRequirement(name = "bearerAuth")
class CommunityController {

	private final CommunityService communityService;

	CommunityController(CommunityService communityService) {
		this.communityService = communityService;
	}

	@PostMapping
	ResponseEntity<CommunityResponse> create(Authentication authentication,
			@Valid @RequestBody CreateCommunityRequest request) {
		CommunityResponse response = communityService.create(currentUserId(authentication), request.name(),
				request.description());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping
	Page<CommunityResponse> list(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return communityService.list(currentUserId(authentication), pageable);
	}

	@GetMapping("/mine")
	Page<CommunityResponse> listMine(Authentication authentication, @PageableDefault(size = 20) Pageable pageable) {
		return communityService.listMine(currentUserId(authentication), pageable);
	}

	@GetMapping("/{communityId}")
	CommunityResponse get(Authentication authentication, @PathVariable UUID communityId) {
		return communityService.get(communityId, currentUserId(authentication));
	}

	@PutMapping("/{communityId}")
	CommunityResponse update(Authentication authentication, @PathVariable UUID communityId,
			@Valid @RequestBody UpdateCommunityRequest request) {
		return communityService.update(communityId, currentUserId(authentication), request.name(),
				request.description());
	}

	@DeleteMapping("/{communityId}")
	ResponseEntity<Void> delete(Authentication authentication, @PathVariable UUID communityId) {
		communityService.delete(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{communityId}/membership")
	ResponseEntity<Void> join(Authentication authentication, @PathVariable UUID communityId) {
		communityService.join(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	/**
	 * Własny status w społeczności — dla zbanowanego jedyne miejsce, w którym widzi
	 * powód i termin swojego bana (ADR 0015).
	 */
	@GetMapping("/{communityId}/membership")
	MembershipStatusResponse membershipStatus(Authentication authentication, @PathVariable UUID communityId) {
		return communityService.membershipStatus(communityId, currentUserId(authentication));
	}

	@DeleteMapping("/{communityId}/membership")
	ResponseEntity<Void> leave(Authentication authentication, @PathVariable UUID communityId) {
		communityService.leave(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{communityId}/members")
	Page<CommunityMemberResponse> listMembers(@PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return communityService.listMembers(communityId, pageable);
	}

	/**
	 * Nadanie/odebranie roli moderatora. Wyłącznie właściciel — moderator mogący
	 * rozdawać role mógłby zdegradować właściciela i przejąć społeczność.
	 */
	@PutMapping("/{communityId}/members/{userId}/role")
	ResponseEntity<Void> setMemberRole(Authentication authentication, @PathVariable UUID communityId,
			@PathVariable UUID userId, @Valid @RequestBody SetMemberRoleRequest request) {
		communityService.setMemberRole(communityId, currentUserId(authentication), userId, request.role());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Ban usuwa członkostwo i blokuje ponowne dołączenie. Samo usunięcie z listy
	 * nic by nie dało — dołączenie jest otwarte i natychmiastowe (Claude.md §8).
	 */
	@PutMapping("/{communityId}/bans/{userId}")
	ResponseEntity<Void> ban(Authentication authentication, @PathVariable UUID communityId, @PathVariable UUID userId,
			@Valid @RequestBody(required = false) BanMemberRequest request) {
		communityService.ban(communityId, currentUserId(authentication), userId,
				request == null ? null : request.reason(), request == null ? null : request.durationDays());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Unban jest też uznaniem wiszącego odwołania (ADR 0017) — to ta sama decyzja,
	 * więc nie ma osobnego endpointu uznania.
	 */
	@DeleteMapping("/{communityId}/bans/{userId}")
	ResponseEntity<Void> unban(Authentication authentication, @PathVariable UUID communityId,
			@PathVariable UUID userId) {
		communityService.unban(communityId, currentUserId(authentication), userId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Odwołanie od własnego bana (ADR 0017). Treść czyta wyłącznie moderacja na
	 * liście banów — kanały push mówią tylko, że odwołanie jest.
	 */
	@PostMapping("/{communityId}/ban-appeal")
	ResponseEntity<Void> appealBan(Authentication authentication, @PathVariable UUID communityId,
			@Valid @RequestBody BanAppealRequest request) {
		communityService.appealBan(communityId, currentUserId(authentication), request.text());
		return ResponseEntity.noContent().build();
	}

	/**
	 * Przekazanie odrzuconego odwołania do administracji platformy — druga
	 * instancja (ADR 0028). Dostępne wyłącznie po odmowie moderacji społeczności:
	 * to ona jest instancją domyślną (§8), a platforma wkracza tylko wtedy, gdy sam
	 * zbanowany ją o to poprosi.
	 */
	@PostMapping("/{communityId}/ban-appeal/escalation")
	ResponseEntity<Void> escalateBanAppeal(Authentication authentication, @PathVariable UUID communityId) {
		communityService.escalateBanAppeal(communityId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	/** Odrzucenie odwołania — ban zostaje, sprawa tego bana jest zamknięta. */
	@PostMapping("/{communityId}/bans/{userId}/appeal/rejection")
	ResponseEntity<Void> rejectBanAppeal(Authentication authentication, @PathVariable UUID communityId,
			@PathVariable UUID userId) {
		communityService.rejectBanAppeal(communityId, currentUserId(authentication), userId);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Lista banów widoczna tylko dla moderatorów. To jedyne miejsce w module, gdzie
	 * lista członków jest niepubliczna — kto jest zbanowany i za co, to informacja
	 * o konkretnej osobie, a nie o społeczności.
	 */
	@GetMapping("/{communityId}/bans")
	Page<CommunityBanResponse> listBans(Authentication authentication, @PathVariable UUID communityId,
			@PageableDefault(size = 20) Pageable pageable) {
		return communityService.listBans(communityId, currentUserId(authentication), pageable);
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
