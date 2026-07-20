# 5. Ręczny fallback "co teraz słucham" zamiast integracji z nieoficjalnymi API

Data: 2026-07-20
Status: zaakceptowane

## Kontekst

Faza 3 roadmapy (Claude.md §15) zakłada próbę integracji z innymi serwisami muzycznymi poza Spotify. Jak ustalono już w Claude.md §7, YouTube Music nie ma oficjalnego publicznego API — dostępne są tylko nieoficjalne, reverse-engineered biblioteki łamiące ToS Google, które mogą przestać działać bez ostrzeżenia. To samo dotyczy w praktyce Apple Music i Tidal dla scenariusza "odczytaj co user teraz słucha" bez własnej aplikacji po stronie tych serwisów. Mimo to user chce mieć widoczny status "co teraz słucham" niezależnie od tego, z jakiego serwisu korzysta.

## Decyzja

Zamiast integrować się z nieoficjalnym API, dodajemy ręczny fallback: `PUT /api/v1/now-playing/manual` (ustaw: źródło, tytuł, artysta, album) i `DELETE /api/v1/now-playing/manual` (wyczyść). Status manualny żyje w Redis pod osobnym kluczem (`now-playing:manual:{userId}`) z własnym, dłuższym TTL (3h domyślnie, konfigurowalne przez `NowPlayingProperties`) i ma pierwszeństwo przed statusem z `MusicProvider` (Spotify), dopóki nie wygaśnie albo user go nie wyczyści. Brak zmian w interfejsie `MusicProvider` i brak nowej tabeli w Postgresie — to dane tak samo ulotne jak cache statusu ze Spotify (Claude.md §7), więc żyją tym samym mechanizmem (Redis + TTL).

## Uzasadnienie

- Zgodnie z rekomendacją Claude.md §7: nie buduje się kluczowej funkcji produktu na czymś, co może zniknąć w każdej chwili bez ostrzeżenia (nieoficjalne API). Ręczny fallback nie ma tego ryzyka.
- Uniwersalność: jeden mechanizm pokrywa Apple Music, Tidal, YouTube Music i każdy przyszły serwis bez integracji OAuth — user sam wpisuje, czego słucha.
- TTL dłuższy niż cache providera (który odświeża się co 30–60s, Claude.md §7) ma sens, bo status ręczny nie jest automatycznie aktualizowany — musi żyć na tyle długo, żeby był użyteczny, ale na tyle krótko, żeby nie pokazywał nieaktualnych danych w nieskończoność.
- Redis zamiast Postgresa: to stan ulotny z natury (tak jak `NowPlayingStatus` z cache Spotify), więc trzymanie go w relacyjnej bazie zamuliłoby ją zapisami bez korzyści (Claude.md §4).

## Konsekwencje

- Priorytet "manual > provider" jest twardo zakodowany w `NowPlayingService.getStatus` — jeśli w przyszłości dojdzie więcej niż jeden provider jednocześnie (Claude.md §7, interfejs `MusicProvider`), trzeba będzie przemyśleć kolejność źródeł na nowo.
- Status manualny wygasa cicho po TTL — user nie dostaje żadnego powiadomienia, że jego "co teraz słucham" przestało być widoczne. Akceptowalne na tym etapie, do rewizji jeśli feedback userów pokaże, że to mylące.
- Integracja z YouTube Music/Apple Music/Tidal przez oficjalne API pozostaje otwarta jako osobna, przyszła decyzja — ten ADR nie zamyka tematu, tylko odracza go do czasu aż (jeśli) pojawi się oficjalne wsparcie.
