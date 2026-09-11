package com.songly.community.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Powód jest opcjonalny, ale przechowywany: to jedyna rzecz, po której da się
 * później odtworzyć, dlaczego ktoś wyleciał — a bany bywają zdejmowane po
 * miesiącach, przez inną osobę niż ta, która je nałożyła.
 *
 * <p>
 * <b>Powód widzi także zbanowany</b> (ADR 0015, {@code GET /membership}) — to
 * nie jest prywatna notatka moderacji. Wewnętrzne uwagi („podejrzenie
 * multikonta X") nie mają tu czego szukać, dopóki nie powstanie osobne pole na
 * notatki.
 *
 * <p>
 * {@code durationDays} — {@code null} to ban bezterminowy (ADR 0014). Doba jest
 * najdrobniejszą jednostką celowo: „przerwa do jutra" to najkrótszy ban, który
 * jeszcze coś znaczy, a godziny kusiłyby do banów-prztyczków. Górnej granicy
 * nie ma — bardzo długi ban czasowy jest po prostu gorszą pisownią
 * bezterminowego i niczym nie grozi.
 */
public record BanMemberRequest(@Size(max = 500) String reason, @Min(1) Integer durationDays) {
}
