# 22. Globalne zawieszanie kont

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

Moderacja w Songly jest społecznościowa, nie globalna (Claude.md §8) — i do tej pory **nie istniała żadna sankcja na poziomie platformy**. Troll zbanowany w pięciu społecznościach dalej miał konto, profil w wyszukiwarce, feed, komentarze, zaproszenia do znajomych i upload mediów; jedyną operacją globalną było usunięcie konta, które może wykonać tylko sam właściciel. ADR 0021 zapisał to jako „największa pozostała luka w moderacji i temat na osobny ADR".

## Decyzja

Zawieszenie konta jako stan na `users` (`suspended_at`, `suspension_reason`, `suspension_expires_at`), nakładane i zdejmowane przez administratora:

| Operacja | Endpoint |
|---|---|
| zawieszenie (powód obowiązkowy, `durationDays` opcjonalne) | `PUT /api/v1/admin/users/{userId}/suspension` |
| zdjęcie | `DELETE /api/v1/admin/users/{userId}/suspension` |
| wykaz aktywnych zawieszeń | `GET /api/v1/admin/suspensions` |
| własny stan (dla zawieszonego) | `GET /api/v1/auth/me/suspension` |

**Egzekwowanie siedzi w uprawnieniach, nie w kodzie modułów**: zawieszone konto dostaje od `JwtAuthenticationFilter` wyłącznie `ROLE_SUSPENDED`, a `anyRequest()` wymaga od teraz `ROLE_USER`.

## Uzasadnienie

- **Jedna bramka zamiast sprawdzenia w każdej ścieżce zapisu — to jest sedno tej decyzji.** Gdyby zawieszenie egzekwować jak blokadę użytkownika (ADR o blokadach: filtr w `ContentService` na siedmiu wyjściach), trzeba by pamiętać o nim w każdym nowym endpointcie każdego modułu, a pominięcie byłoby niewidoczne w testach funkcjonalnych. Przy odwróceniu domyślnej odpowiedzi (`anyRequest()` wymaga `ROLE_USER`, nie samego uwierzytelnienia) **nowy endpoint jest domyślnie zamknięty dla zawieszonego**. Dokładnie ta sama figura, co gating całego prefiksu `/api/v1/admin/**` w ADR 0021.
- **Zawieszenie odcina też odczyt, w przeciwieństwie do banu w społeczności.** Tam ukrywanie treści byłoby ochroną pozorną, bo społeczności są publiczne i wystarczy się wylogować (§8). Tu jest odwrotnie: sankcja dotyczy **konta**, a konto służy do czytania rzeczy nie-publicznych (feed znajomych, statusy „co teraz gram", skrzynka). Zawieszone konto nie ma być obecne w produkcie, a nie tylko ma przestać pisać.
- **Logowanie zawieszonego przechodzi i to jest celowe.** Twardy blok na logowaniu byłby prostszy, ale odciąłby **prawo do usunięcia danych** (RODO, §9) — sankcja nie może unieważniać uprawnienia, którego nie wolno odebrać. Dlatego tokeny są wydawane normalnie, tylko niosą puste uprawnienia; dostępne są trzy rzeczy: poznać swój stan, wylogować się, usunąć konto. Alternatywa (deletion po tokenie z maila, jak reset hasła) byłaby dużo większą i znacznie groźniejszą konstrukcją.
- **Skutek jest natychmiastowy, bez unieważniania sesji.** Stan konta czytamy na żywo przy każdym żądaniu (ADR 0006), więc istniejący access token traci moc od razu — tak samo jak przy odebraniu roli admina (ADR 0021). To trzeci raz, kiedy ten koszt (zapytanie na żądanie) się zwraca, i dlatego `AccountStatusService` pobiera rolę **i** zawieszenie **jednym** zapytaniem: dwa osobne podwoiłyby najgorętszy odczyt w aplikacji.
- **Zawieszony dostaje własny kod błędu** (`ACCOUNT_SUSPENDED` z `RestAccessDeniedHandler`), nie ogólne `FORBIDDEN`. Bez tego widziałby aplikację, w której losowo wszystko przestało działać — decyzję wyglądającą jak awaria, czyli problem, od którego zaczął się ADR 0012.
- **Powód jest obowiązkowy** (w odróżnieniu od powodu banu, ADR 0011) i **widoczny dla zawieszonego**, ale wyłącznie kanałem pull. Mail mówi „konto zawieszone" i podaje termin; powodu nie niesie, bo to tekst administratora, a kanały push nie przenoszą cudzych tekstów (ADR 0015). Termin owszem — zawieszenie czasowe nieodróżnialne od bezterminowego mija się z celem (ADR 0014).
- **Mail jest tu jedynym kanałem powiadomienia**, odwrotnie niż przy banie: skrzynka w aplikacji jest dla zawieszonego niedostępna, więc zapis w niej byłby informacją zamkniętą przed adresatem. Zdarzenie leci tylko przy **przejściu** konto aktywne → zawieszone, więc poprawienie literówki w powodzie nie wysyła drugiego maila.
- **Wygasanie leniwe, jak przy banach czasowych** (ADR 0014): nic nie dzieje się w chwili wygaśnięcia, a każde pytanie o stan filtruje po terminie. Konto wraca samo i nie potrzebuje zadania sprzątającego.
- **Dwa zakazy przeniesione z ADR 0011: nie siebie i nie innego administratora.** Pierwszy, bo admin zawieszający siebie straciłby dostęp do endpointu, którym mógłby to cofnąć — czyli wracamy do ręcznego SQL-a, który ADR 0021 właśnie usunął. Drugi, bo inaczej dwóch adminów mogłoby się wzajemnie wyłączyć i rozstrzygałoby to, kto pierwszy kliknął; zawieszenie admina wymaga więc najpierw odebrania mu roli, czyli dwóch świadomych kroków.
- **Kolumny na `users`, bez `suspended_by`.** Jedno konto to jedno zawieszenie, czyli atrybut tego konta (ten sam argument co przy odwołaniach w ADR 0017). Kolumny „kto zawiesił" świadomie nie ma: byłaby kluczem obcym tabeli do samej siebie, wymagającym zerowania przy kasowaniu konta administratora, a odpowiedź niesie wpis audytowy i licznik (ADR 0021).

## Konsekwencje

- **Brak ścieżki odwoławczej od zawieszenia — i to jest dziś niepełne.** ADR 0017 ustalił, że „wiedzieć i nie móc nic zrobić to lepiej opisana ściana"; zawieszony zna powód i termin, ale nie ma jak zakwestionować decyzji. Odwołanie wymaga własnego przepływu (kolejka dla adminów, jedno odwołanie na zawieszenie, uznanie = zdjęcie) i jest naturalnym następnym krokiem, zrobionym na wzór ADR 0017. **Świadomie osobny PR**, żeby ta zmiana nie rosła ponad to, co da się przejrzeć.
- **Zawieszone konto zostaje widoczne dla innych**: profil dalej jest w wyszukiwarce, a posty w feedach. Ukrycie treści zawieszonego to osobna decyzja — i nietrywialna, bo „odwieszenie przywraca wszystko" wymaga filtrowania przy odczycie, a nie kasowania (ta sama zasada co przy blokadach).
- **Preferencje poczty (ADR 0020) obejmują też mail o zawieszeniu** (kategoria „decyzje o mnie"), więc da się go nie dostać. Zapis o decyzji zostaje dostępny przez `GET /auth/me/suspension`, czyli kanałem pull — spójnie z tym, jak te preferencje działają dla banów.
- **`AdminStatusService` zniknął**, zastąpiony przez `AccountStatusService` zwracający `AccountStatus(admin, suspended)`. Powód jest wydajnościowy i wprost mierzalny: filtr wykonuje jedno zapytanie na żądanie, nie dwa.
- **Zmiana `anyRequest()` dotyczy każdego endpointu w aplikacji**, więc pełny przebieg testów jest tu jedyną sensowną weryfikacją — 415 testów przechodzi bez zmian w pozostałych modułach, co potwierdza, że nowa reguła nie zwęża niczego dla kont aktywnych.
