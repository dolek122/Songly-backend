package com.songly.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Treść odwołania jest obowiązkowa — odwołanie bez uzasadnienia nie daje
 * administracji niczego do rozpatrzenia. Limit większy niż powód zawieszenia
 * (500), bo odwołujący odpowiada na ten powód i potrzebuje miejsca na kontekst
 * (ADR 0023, jak ADR 0017).
 */
public record SuspensionAppealRequest(@NotBlank @Size(max = 1000) String text) {
}
