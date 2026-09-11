package com.songly.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Treść odwołania jest obowiązkowa — odwołanie bez uzasadnienia nie daje
 * moderacji niczego do rozpatrzenia. Limit większy niż powód bana (500), bo
 * odwołujący odpowiada na powód i potrzebuje miejsca na kontekst.
 */
public record BanAppealRequest(@NotBlank @Size(max = 1000) String text) {
}
