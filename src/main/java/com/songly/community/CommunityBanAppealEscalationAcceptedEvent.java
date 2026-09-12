package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Administracja platformy uchyliła ban społeczności (ADR 0028) — druga
 * instancja przyznała rację zbanowanemu.
 *
 * <p>
 * Osobne zdarzenie od {@code CommunityBanAppealAcceptedEvent}, mimo tego samego
 * skutku: tam ban zdjęła własna moderacja społeczności, tutaj <b>ktoś z
 * zewnątrz</b>. Wiadomość mówiąca „moderacja uznała odwołanie" byłaby w tym
 * drugim przypadku po prostu nieprawdziwa, a moderatorzy muszą się dowiedzieć,
 * że ich decyzja została uchylona — inaczej zbanowany wraca do społeczności bez
 * żadnego wyjaśnienia i wygląda to na awarię.
 */
public record CommunityBanAppealEscalationAcceptedEvent(UUID communityId, UUID userId, UUID adminId,
		Instant occurredAt) {
}
