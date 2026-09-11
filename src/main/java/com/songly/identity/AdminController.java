package com.songly.identity;

import com.songly.identity.dto.AdminListResponse;
import com.songly.identity.dto.SuspendAccountRequest;
import com.songly.identity.dto.SuspendedAccountResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operacje administracyjne (ADR 0021). Całe {@code /api/v1/admin/**} jest
 * zamknięte rolą w {@code SecurityConfig} — gating ścieżką, nie adnotacją na
 * metodzie, bo wtedy nowy endpoint w tym kontrolerze jest chroniony domyślnie,
 * a nie dopiero gdy ktoś pamięta o adnotacji.
 */
@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
class AdminController {

	private final AdminRoleService adminRoleService;
	private final AccountSuspensionService suspensionService;

	AdminController(AdminRoleService adminRoleService, AccountSuspensionService suspensionService) {
		this.adminRoleService = adminRoleService;
		this.suspensionService = suspensionService;
	}

	@GetMapping("/admins")
	AdminListResponse admins() {
		return adminRoleService.admins();
	}

	@PutMapping("/users/{userId}/admin")
	ResponseEntity<Void> grantAdmin(Authentication authentication, @PathVariable UUID userId) {
		adminRoleService.grant(userId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/users/{userId}/admin")
	ResponseEntity<Void> revokeAdmin(Authentication authentication, @PathVariable UUID userId) {
		adminRoleService.revoke(userId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/suspensions")
	List<SuspendedAccountResponse> suspensions() {
		return suspensionService.suspendedAccounts();
	}

	@PutMapping("/users/{userId}/suspension")
	ResponseEntity<Void> suspend(Authentication authentication, @PathVariable UUID userId,
			@Valid @RequestBody SuspendAccountRequest request) {
		suspensionService.suspend(userId, currentUserId(authentication), request.reason(), request.durationDays());
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/users/{userId}/suspension")
	ResponseEntity<Void> liftSuspension(Authentication authentication, @PathVariable UUID userId) {
		suspensionService.lift(userId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
