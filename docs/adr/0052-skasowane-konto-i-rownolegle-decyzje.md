# 52. Token skasowanego konta i równoległe decyzje

Data: 2026-11-20
Status: zaakceptowane

## Kontekst

Przegląd backendu wysyłał te same żądania naraz i używał tokenu konta już usuniętego. Pojedynczo wszystko działało, a w tych dwóch sytuacjach serwer zapisywał stan, którego żadna sekwencja kliknięć by nie dała.

**Token usuniętego konta działał do wygaśnięcia, czyli do 15 minut.** `JwtAuthenticationFilter` i tak czyta konto z bazy przy każdym żądaniu (ADR 0006), ale brak wiersza traktował jak zwykłe konto bez roli i bez zawieszenia. Zakładaliśmy, że moduły i tak nie znajdą danych takiego konta. Nie musiały ich znaleźć: feed, wyszukiwarka, ręczny status i nowy strumień SSE czytają cudze dane albo piszą nowe. Już otwarty strumień wisiał do swojego timeoutu, czyli do 30 minut.

**Równoległe żądania mijały sprawdzenia stanu.** Każde sprawdzało stan, zanim poprzednie zdążyło go zmienić:

| Żądania naraz | Skutek |
|---|---|
| 3× odrzucenie tego samego zgłoszenia | 3 decyzje: przydział zgłaszającego spadał z 20 do 5 (ADR 0025), do tego 3 powiadomienia |
| 3× odwołanie od tego samego bana | 2 przyjęte, 2 powiadomienia dla moderacji |
| 3× odwołanie od zawieszenia | kilka przyjętych |
| 3× refresh tym samym tokenem | 3 niezależne, ważne łańcuchy sesji; wykrywanie kradzieży (§9) ich nie widziało, bo żaden nie używał odwołanego tokenu |
| zaproszenia A→B i B→A | 2 oczekujące zaproszenia zamiast znajomości |
| społeczności „Dup” i „dup” | obie powstawały; ograniczenie w bazie rozróżniało wielkość liter, a kod jej nie rozróżniał |

## Decyzja

### Token skasowanego konta

- `AccountStatusService.statusOf` zwraca `Optional`.
- Gdy konta nie ma, filtr nie uwierzytelnia żądania, więc chronione ścieżki odpowiadają 401 `UNAUTHENTICATED`, jak przy każdym nieważnym tokenie.
- Koszt jest zerowy: to samo zapytanie było już wykonywane przy każdym żądaniu.
- Otwarte strumienie SSE usuniętego konta zamyka listener `AFTER_COMMIT` (`NowPlayingStreamHub.closeAll`). Usunięcie, które się wycofa, nie rozłączy więc konta, które dalej istnieje.

### Decyzje na istniejącym wierszu: blokada wiersza

Odrzucenie zgłoszenia, każda decyzja o banie (nałożenie, zdjęcie, odwołanie, eskalacja, rozstrzygnięcia), każda decyzja o zawieszeniu i odświeżenie tokenu czytają wiersz przez `…ForUpdate`, czyli `SELECT … FOR NO KEY UPDATE`. Drugie żądanie czeka, aż pierwsze się zatwierdzi, i widzi już nowy stan. Odpowiada więc dokładnie tak jak przy kliknięciach po kolei:

- drugie odrzucenie zgłoszenia to idempotentne 204 bez nowej decyzji;
- drugie odwołanie dostaje 409 `BAN_APPEAL_ALREADY_SUBMITTED`;
- drugi refresh trafia w wykrywanie ponownego użycia i odwołuje wszystkie sesje konta.

### Nowe wiersze: indeks w bazie

Gdy wiersz dopiero powstaje, nie ma czego zablokować, więc rozstrzyga indeks unikalny (V34):

- **Zaproszenia:** jedno oczekujące na parę, w którąkolwiek stronę (`LEAST`/`GREATEST`). Przegrany dostaje 409 `CONFLICT` (§6). Ponowienie trafia już w zaproszenie w drugą stronę i zamienia je w znajomość.
- **Społeczności:** nazwa unikalna po `lower(name)`. Przegrany dostaje ten sam `COMMUNITY_NAME_TAKEN` co drugi klikający po kolei, bo `CommunityService` tłumaczy naruszenie tego indeksu.
- **Istniejące dane:** migracja naprawia to, co wyścig mógł już zostawić. Pary zaproszeń w obie strony stają się znajomością. Duplikaty nazw różniące się wielkością liter dostają przyrostek „(2)”, „(3)”, a najstarsza społeczność zachowuje nazwę.

## Uzasadnienie

- **Blokada wiersza, nie warunkowy `UPDATE` ani `@Version`.**
  - Warunkowy `UPDATE` wymagałby osobnego zapytania dla każdej decyzji i zmiany odpowiedzi przegranego: zamiast błędu domenowego, który klient już obsługuje, dostałby ogólne „0 wierszy”.
  - `@Version` daje przegranemu 409 `CONFLICT`. To prawda, ale gorsza od odpowiedzi, którą dostałby przy kliknięciach po kolei, a do tego wymaga migracji czterech tabel.
  - Blokada zmienia jedną linię na ścieżkę i nie zmienia żadnej odpowiedzi. Blokuje tylko jeden wiersz na krótką transakcję, a odczyty bez blokady (filtr JWT, listy) jej nie czekają.
- **Refresh: przegrany to ponowne użycie.** Równoległego użycia tego samego tokenu nie da się odróżnić od złodzieja, który zdążył w tej samej chwili. To ten sam świadomy koszt, który §9 opisuje dla ponowienia po zerwanym połączeniu. Aplikacja mobilna nie wywołuje go sama: wtyczka `Auth` w Ktorze skleja równoległe 401 w jedno odświeżenie.
- **Przegrany wyścig o zaproszenie dostaje ogólne 409.** Zaproszenie w drugą stronę zamienia się w znajomość, ale przegrana transakcja jest już przerwana przez naruszenie indeksu, więc nie da się w niej dokończyć akceptacji. Ponowienie żądania kończy się znajomością.

Wszystkie wyścigi z tabeli sprawdza `ConcurrentDecisionsIntegrationTest`, a każdą poprawkę sprawdziłem mutacją (§11). Równoległych żądań w teście jest mniej niż połączeń w puli testów, bo inaczej test sprawdzałby zakleszczenie z ADR 0051, a nie wyścig.

## Konsekwencje

- **Starszy token przestaje działać od razu tylko przy usunięciu konta.** Zmiana hasła dalej zostawia wydane access tokeny do wygaśnięcia (§9), bo konto istnieje, a sprawdzanie sesji przy każdym żądaniu oddałoby bezstanowość JWT.
- **Strumienie zamyka tylko instancja, która usunęła konto.** Na Renderze jest jedna. Przy kilku instancjach strumień na innej nie niósłby już niczego, bo znajomości znikają z kontem, i zamknąłby się po timeoucie.
- **Nowa decyzja o banie, zawieszeniu, zgłoszeniu albo sesji musi czytać wiersz przez `…ForUpdate`.** Zwykłe `findById` przywraca wyścig, a żaden test pojedynczych żądań tego nie wyłapie.
- **Właściciel społeczności, której nazwa dostała przyrostek, może ją zmienić.** Zmiana na nazwę zajętą bez względu na wielkość liter dalej daje `COMMUNITY_NAME_TAKEN`.
