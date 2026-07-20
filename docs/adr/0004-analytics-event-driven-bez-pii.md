# 4. Analytics: agregaty zasilane eventami, bez PII i bez roli admina na start

Data: 2026-07-20
Status: zaakceptowane

## Kontekst

Faza 2 roadmapy (Claude.md §15) wymaga podstawowej analityki produktowej — żeby wiedzieć, co realnie działa (rejestracje, lajki, komentarze, share'y, zaproszenia znajomych, dołączenia do community), zanim zacznie się inwestować w cokolwiek bardziej zaawansowanego (np. rekomendacje ML, Claude.md §8).

## Decyzja

Nowy moduł `analytics` nasłuchuje tych samych eventów Springa (`@TransactionalEventListener`, `AFTER_COMMIT`), które już konsumuje moduł `notification` — bez sięgania po encje ani repozytoria innych modułów (Claude.md §2). Zapisywane jest wyłącznie zliczenie zdarzenia danego typu w danym momencie (`AnalyticsEventType` + timestamp), bez identyfikatora użytkownika ani innych danych osobowych. `GET /api/v1/analytics/summary?days=N` zwraca zagregowane liczby per typ w oknie czasowym (domyślnie 7 dni), dostępne dla każdego zalogowanego użytkownika — nie ma jeszcze roli admina w systemie.

## Uzasadnienie

- Ten sam wzorzec event-driven co `notification` (Claude.md §2) — moduł nie wie nic o tym, kto i dlaczego wygenerował zdarzenie, tylko że ono zaszło. Łatwo dodać kolejny typ zdarzenia bez zmian w modułach źródłowych.
- Brak identyfikatora użytkownika w zapisanym zdarzeniu to świadome ograniczenie zakresu danych: to są liczniki biznesowe, nie dane behawioralne per-user, więc nie podlegają tym samym rygorom RODO co np. status "co teraz słucham" (Claude.md §9).
- Gating tylko uwierzytelnieniem (bez roli admina) to świadomy kompromis na tym etapie — dane nie zawierają PII, więc ryzyko ekspozycji jest niskie, a budowanie systemu ról tylko dla jednego endpointu byłoby przedwczesne.

## Konsekwencje

- Gdy w systemie pojawi się rola admina (do czegokolwiek innego), `GET /api/v1/analytics/summary` powinien dostać gating tą rolą — to jest dług, nie docelowy stan.
- Jeśli w przyszłości analytics będzie potrzebować danych per-user (np. lejek konwersji, cohorty), to nowa decyzja i osobny ADR — obecny model celowo tego nie robi.
- Nowy typ zdarzenia domenowego w dowolnym module = nowy listener w `AnalyticsEventListener` + nowa wartość `AnalyticsEventType`, zero zmian w module źródłowym.
