package com.songly.identity;

import com.songly.identity.dto.AdminListResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
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

	AdminController(AdminRoleService adminRoleService) {
		this.adminRoleService = adminRoleService;
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

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
