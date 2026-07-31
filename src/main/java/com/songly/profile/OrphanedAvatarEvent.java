package com.songly.profile;

/**
 * Klucz avatara przestał być przez cokolwiek wskazywany — plik można skasować.
 *
 * <p>
 * Zdarzenie wewnętrzne modułu (stąd brak {@code public}), istniejące po to,
 * żeby kasowanie pliku odbyło się <b>po commicie</b>. To jest odwrotna faza niż
 * przy kasowaniu wierszy (Claude.md §9), i różnica nie jest kosmetyczna:
 * wiersze muszą zniknąć w tej samej transakcji, bo trzyma je klucz obcy, a plik
 * musi zniknąć dopiero wtedy, gdy baza potwierdzi, że nikt już go nie wskazuje.
 * Skasowany za wcześnie zostawia po rollbacku wiersz wskazujący na
 * nieistniejący plik — czyli awatar, który nigdy się nie wyświetli i którego
 * nie da się odzyskać.
 *
 * <p>
 * Odwrotna pomyłka jest tania: osierocony plik kosztuje ułamek grosza i da się
 * go znaleźć później.
 */
record OrphanedAvatarEvent(String key) {
}
