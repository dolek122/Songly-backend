package com.songly.identity;

import com.songly.common.web.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Administrator nie zawiesza innego administratora (ADR 0022) — inaczej dwóch
 * adminów mogłoby się wzajemnie wyłączyć, a rozstrzygałoby to, kto pierwszy
 * kliknął (ta sama reguła co „moderator nie banuje moderatora", ADR 0011).
 * Zawieszenie admina wymaga najpierw odebrania mu roli, czyli dwóch świadomych
 * kroków.
 */
class CannotSuspendAdminException extends ApiException {

	CannotSuspendAdminException() {
		super(HttpStatus.FORBIDDEN, "CANNOT_SUSPEND_ADMIN",
				"Najpierw odbierz uprawnienia administratora, potem zawieś konto");
	}
}
