package com.songly.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Bez {@code avatarUrl} — celowo (ADR 0010). Wcześniej było to dowolne pole
 * tekstowe, więc adres pod kontrolą obcej osoby lądował w kliencie każdego, kto
 * obejrzał ten profil: gotowy piksel śledzący cudze IP, a przy okazji możliwość
 * podmiany zdjęcia na cokolwiek w dowolnej chwili, bez wgrywania czegokolwiek
 * do nas.
 *
 * <p>
 * Avatar ustawia się teraz wyłącznie przez {@code POST /profiles/me/avatar},
 * czyli plikiem, który przeszedł naszą walidację i leży w naszym magazynie.
 */
public record UpdateProfileRequest(@NotBlank @Size(max = 80) String displayName, @Size(max = 500) String bio) {
}
