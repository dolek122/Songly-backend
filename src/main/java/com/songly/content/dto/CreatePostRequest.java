package com.songly.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code imageKey} jest opcjonalny i pochodzi z wcześniejszego
 * {@code POST /api/v1/posts/image}.
 *
 * <p>
 * Dwa kroki zamiast jednego żądania multipart, bo wgranie pliku i opublikowanie
 * treści mają zupełnie różne charakterystyki: plik idzie długo i bywa
 * ponawiany, treść leci natychmiast. Sklejone w jedno żądanie kazałyby
 * klientowi przesyłać zdjęcie od nowa za każdym razem, gdy sama publikacja się
 * nie uda.
 */
public record CreatePostRequest(@NotBlank @Size(max = 1000) String content, @Size(max = 512) String imageKey) {
}
