package com.songly.content;

/**
 * Zdjęcie posta przestało być przez cokolwiek wskazywane — plik można skasować.
 *
 * <p>
 * Ta sama zasada co przy avatarach: wiersze giną w transakcji, pliki dopiero po
 * jej commicie. Skasowanie pliku wcześniej zostawia po rollbacku post ze
 * zdjęciem, którego nie da się już wyświetlić ani odzyskać; odwrotna pomyłka
 * kosztuje osierocony plik.
 */
record OrphanedPostImageEvent(String key) {
}
