/**
 * Historia decyzji moderacyjnych (ADR 0024): {@code ModerationLogEntry}.
 *
 * <p>
 * Moduł wyłącznie <b>zapisujący i czytający</b> — nie podejmuje żadnych decyzji
 * i nie egzekwuje niczego. Konsumuje te same zdarzenia domenowe co
 * {@code notification} i {@code analytics}, bez dostępu do encji modułów, które
 * je publikują (Claude.md §2).
 */
package com.songly.moderationlog;
