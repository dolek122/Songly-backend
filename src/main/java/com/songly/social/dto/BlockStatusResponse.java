package com.songly.social.dto;

/**
 * Czy pytający blokuje danego użytkownika (ADR 0039). Wyłącznie ten kierunek:
 * „czy on blokuje mnie" zdradzałoby cudzą decyzję, którą reszta API celowo
 * ukrywa za 404 (Claude.md §9).
 */
public record BlockStatusResponse(boolean blocked) {
}
