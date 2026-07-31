# 8. Reset hasła przez e-mail i pierwszy kanał pocztowy w aplikacji

Data: 2026-07-31
Status: zaakceptowane

## Kontekst

System miał trzy operacje na haśle — rejestrację, logowanie i zmianę hasła (`PUT /api/v1/auth/password`) — i wszystkie trzy wymagały znajomości hasła. Użytkownik, który je zapomniał, nie miał **żadnej** drogi powrotu do konta; jedynym wyjściem było założenie nowego i utrata całej historii, znajomych i społeczności.

To nie była wyłącznie luka w wygodzie. Claude.md §9 uzasadnia dwa mechanizmy bezpieczeństwa zdaniem, że użytkownik „wraca hasłem, którego złodziej nie ma":

- zmiana hasła odwołuje **wszystkie** sesje, łącznie z bieżącą,
- wykrycie ponownego użycia refresh tokenu odwołuje **wszystkie** sesje użytkownika.

Oba te zdania były prawdziwe tylko dla ludzi pamiętających hasło. Dla reszty zabezpieczenie przed kradzieżą tokenu kończyło się trwałą utratą konta — czyli mechanizm chroniący przed przejęciem robił dokładnie to, przed czym miał chronić.

Dodatkowo aplikacja nie miała **żadnego** kanału wychodzącego do użytkownika. Brak takiego kanału był ukrytym ograniczeniem dla całej klasy funkcji (weryfikacja adresu e-mail, powiadomienie o logowaniu z nowego urządzenia, ostrzeżenie przed usunięciem konta), nie tylko dla resetu hasła.

## Decyzja

**Kanał e-mail** jako infrastruktura w `common.mail`: publiczny interfejs `MailSender` (`send(to, subject, body)`) z dwiema implementacjami wybieranymi propercją `songly.mail.provider` — `log` (domyślna, wypisuje wiadomość do logu) i `smtp` (`spring-boot-starter-mail`). Interfejs nie zna domeny; treść składa moduł, który wysyła (Claude.md §3, reguła 3).

**Reset hasła** jako dwa publiczne endpointy w module `identity`:

- `POST /api/v1/auth/password/reset-request` — przyjmuje adres, zwraca **202 zawsze**,
- `POST /api/v1/auth/password/reset` — przyjmuje token i nowe hasło, zwraca 204.

Token: 256 losowych bitów, w bazie (`password_reset_tokens`, migracja `V14`) wyłącznie skrót SHA-256, TTL 30 minut, jednorazowy, jeden żywy na konto. Udany reset odwołuje wszystkie sesje i kasuje wszystkie tokeny resetu tego konta.

## Uzasadnienie

- **Żaden z endpointów nie zdradza, czy konto istnieje.** 202 na nieistniejący adres, 202 na żądanie wyciszone odstępem, jeden kod błędu (`INVALID_PASSWORD_RESET_TOKEN`) na token nieznany, wygasły i zużyty. Bez tego endpoint byłby wygodniejszą wersją tej samej wyroczni, którą logowanie starannie zamyka (Claude.md §13). Konsekwencje idą dalej, niż widać na pierwszy rzut oka: błąd wysyłki maila **nie może** wypływać jako 500, bo 500 zdarzałoby się wyłącznie dla adresów z kontem.
- **Skrót zamiast tokenu w bazie**, tak samo jak przy refresh tokenach. Wyciek bazy bez tego byłby natychmiastowym przejęciem każdego konta z oczekującym resetem. SHA-256 bez soli wystarcza, w przeciwieństwie do haseł: token jest w pełni losowy, więc nie ma czego zgadywać słownikiem.
- **Reset odwołuje wszystkie sesje** — dokładnie jak zmiana hasła (§9). Reset robi się po utracie kontroli nad kontem; zostawienie w mocy sesji sprzed resetu przekreślałoby całą operację.
- **Jeden żywy token na konto.** Kolejne „nie dostałem maila" nie może zostawiać w obiegu następnych ważnych kluczy, bo użytkownik nie ma sposobu, żeby je unieważnić. Efekt uboczny: tabela nie rośnie bez ograniczenia, więc — inaczej niż `sessions` — nie potrzebuje zadania sprzątającego.
- **Odstęp między wysyłkami dla tego samego konta** (`resend-interval`, domyślnie 1 min) obok rate limitingu per IP. Rate limiting sam tego nie załatwia: liczy per adres źródłowy, a ofiara zasypywania pocztą jest jedna. Bez tego endpoint byłby darmowym mailerem wycelowanym w cudzą skrzynkę.
- **Wysyłka `AFTER_COMMIT`**, przez wewnętrzne zdarzenie modułu. Mail wysłany wewnątrz transakcji, która się potem wycofa, niesie link do tokenu, którego w bazie nigdy nie było. Przy okazji rozmowa z serwerem SMTP nie trzyma otwartej transakcji bazodanowej. To odwrotność listenerów kasowania konta (§9), które muszą zdążyć **przed** commitem — obie decyzje wynikają z tego samego pytania „co musi być prawdą w bazie, gdy ten kod się wykonuje".
- **`provider=log` domyślnie.** Instancja, która przez pomyłkę wyśle prawdziwą wiadomość, jest gorsza niż taka, która przez pomyłkę tylko ją zaloguje. Odwrotnie na produkcji: `provider=smtp` bez skonfigurowanego `spring.mail.host` **wywala start aplikacji**, zamiast po cichu zejść do logowania — instancja, która wystartowała i tylko wygląda, jakby wysyłała maile, to awaria, której nikt nie zauważa aż do pierwszego „nie dostałem linku".
- **Link prowadzi do aplikacji klienckiej**, nie do backendu. Backend nie ma UI, więc wysłanie użytkownika wprost na endpoint REST pokazałoby mu surowy JSON.
- **Zmiana hasła unieważnia oczekujący link resetujący.** Taki link najczęściej zamówił ktoś obcy; zostawienie go żywego jeszcze przez pół godziny znaczyłoby, że reakcja właściciela niczego nie zamknęła.

## Konsekwencje

- `password_reset_tokens` ma FK do `users` bez `ON DELETE CASCADE`, jak wszystko inne (§9). `AuthService.deleteAccount` kasuje te wiersze **bezpośrednio**, a nie przez zdarzenie — tabela należy do tego samego modułu, a zdarzenie istnieje po to, żeby nie sięgać do cudzych. `AccountDeletionFlowIntegrationTest` zamawia reset przed usunięciem konta, żeby ten klucz obcy był realnie dotknięty.
- Na produkcji trzeba ustawić `MAIL_PROVIDER=smtp`, `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, `MAIL_FROM` i `PASSWORD_RESET_URL`. `spring.mail.host` celowo **nie jest** zadeklarowany w `application.properties` — autokonfiguracja poczty włącza się na samą obecność tego klucza, więc pusty default znaczyłby „serwer o pustym adresie" i odebrałby `MailConfig` jedyny sposób odróżnienia SMTP skonfigurowanego od nieskonfigurowanego (ta sama pułapka co przy `spring.data.redis.url`).
- `provider=log` na produkcji byłby luką: token trafia wtedy do logów w postaci jawnej, więc każdy z dostępem do logów mógłby przejmować konta. Dlatego wybór providera jest jawną propercją, a nie czymś wywnioskowanym.
- Świadomie **poza zakresem**: weryfikacja adresu e-mail przy rejestracji. Rejestracja nadal zwraca 409 `EMAIL_ALREADY_USED` z adresem w treści, czyli pozostaje wyrocznią na istnienie kont — inaczej niż logowanie i reset. Domknięcie tego wymaga pełnego przepływu weryfikacji (rejestracja nie może od razu wydawać tokenów), czyli osobnej decyzji. Kanał e-mail z tego ADR jest brakującym warunkiem, żeby dało się ją w ogóle podjąć.
- Nowa metryka `songly.auth.password-reset.attempts{outcome}` — serie `requested`, `throttled`, `success`, `invalid-token`. Celowo **bez** serii rozróżniającej istnienie konta, konsekwentnie z §13.
