package com.songly.community.dto;

import jakarta.validation.constraints.Size;

/**
 * Powód jest opcjonalny, ale przechowywany: to jedyna rzecz, po której da się
 * później odtworzyć, dlaczego ktoś wyleciał — a bany bywają zdejmowane po
 * miesiącach, przez inną osobę niż ta, która je nałożyła.
 */
public record BanMemberRequest(@Size(max = 500) String reason) {
}
