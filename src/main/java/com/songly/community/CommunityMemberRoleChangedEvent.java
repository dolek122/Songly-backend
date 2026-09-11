package com.songly.community;

import java.time.Instant;
import java.util.UUID;

/**
 * Właściciel zmienił komuś rolę w społeczności (ADR 0016).
 *
 * <p>
 * Publikowane <b>tylko przy faktycznej zmianie</b> — ustawienie roli, którą
 * członek już ma, jest idempotentnym brakiem zmiany, jak ponowny ban osoby z
 * banem aktywnym (ADR 0014). Bez tego warunku powtórzone kliknięcie właściciela
 * dokładałoby kolejne powiadomienie o tym samym.
 *
 * <p>
 * Kierunek zmiany jedzie jako {@code moderationGranted}, <b>nie</b> jako
 * {@code CommunityRole}: enum ról to wewnętrzny słownik tego modułu i konsument
 * zdarzenia nie ma prawa go dotykać (reguła 1 {@code ModuleBoundariesTest} —
 * złapała dokładnie tę wersję z rolą w polu). Przy jedynych osiągalnych
 * przejściach MEMBER↔MODERATOR ({@code OWNER} jest w {@code setMemberRole}
 * nieosiągalny) boolean niesie pełną informację.
 */
public record CommunityMemberRoleChangedEvent(UUID communityId, UUID userId, UUID changedBy, boolean moderationGranted,
		Instant occurredAt) {
}
