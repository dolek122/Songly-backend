package com.songly.community.dto;

import com.songly.community.CommunityRole;
import jakarta.validation.constraints.NotNull;

/**
 * {@code OWNER} jest tu formalnie dopuszczalny przez typ, ale odrzucany przez
 * serwis: właścicielstwo to osobne pole na społeczności, nie stopień w tej
 * samej skali. Walidacja na poziomie typu wymagałaby drugiego enuma różniącego
 * się jedną wartością — kosztem większym niż jeden jawny warunek w serwisie.
 */
public record SetMemberRoleRequest(@NotNull CommunityRole role) {
}
