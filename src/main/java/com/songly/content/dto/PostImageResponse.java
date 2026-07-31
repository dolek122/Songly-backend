package com.songly.content.dto;

/**
 * Wynik wgrania zdjęcia: {@code key} wraca do nas w
 * {@link CreatePostRequest#imageKey()}, {@code url} służy klientowi do
 * pokazania podglądu, zanim post powstanie.
 */
public record PostImageResponse(String key, String url) {
}
