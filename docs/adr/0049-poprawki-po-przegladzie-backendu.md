# 49. Poprawki po przeglądzie backendu

Data: 2026-10-26
Status: zaakceptowane

## Kontekst

Przegląd całego backendu po ADR 0043–0048 znalazł kilkanaście miejsc, w których kod nie robił tego, co obiecywały wcześniejsze decyzje albo CLAUDE.md. Żadne z nich nie wymagało zmiany kontraktu API ani nowego mechanizmu. Każde było luką w istniejącej regule. Zbieramy je w jednym ADR-ze, bo osobno każde jest za małe na decyzję, a razem mówią, które twierdzenia z dokumentacji przestały być prawdą i od kiedy znowu są.

Trzy znaleziska z tego samego przeglądu czekają na osobne decyzje, bo zmieniają kontrakt albo przepływ: hasło przy potwierdzeniu rejestracji, ochrona callbacku OAuth Spotify przed CSRF i powiadomienia o decyzjach moderatora, który usunął konto.

## Decyzja

### Identity

- **Ostrzeżenie „ktoś próbował założyć konto” ma ten sam odstęp co link dla wolnego adresu** (`resend-interval`, slot w Redisie, fail-open). Bez odstępu ta gałąź była darmowym mailerem wycelowanym w skrzynkę każdego użytkownika. Odstęp ADR 0009 obejmował wyłącznie gałąź „adres wolny”.
- **Sprzątanie sesji kasuje wiersz dopiero po `expires_at`**, także odwołany. Wykrywanie kradzieży refresh tokenu (§9) rozpoznaje kopię wyłącznie po jej odwołanym wierszu. Gdy wiersze znikały tydzień po `revoked_at`, prawowity użytkownik wracający po ośmiu dniach dostawał zwykłe 401, a łańcuch złodzieja żył dalej.
- **Access token nie niesie adresu e-mail.** Serwer czytał z tokenu wyłącznie `sub`, a adres wędrował z każdym żądaniem do logów proxy i raportów awarii (§13).
- **Limit hasła liczony w bajtach UTF-8** (`@MaxUtf8Bytes(72)` w `common.validation`). Bcrypt przyjmuje 72 bajty i od Spring Security 7 odrzuca dłuższe wyjątkiem przy `encode`. `@Size(max = 72)` liczy znaki, więc 40 polskich liter przechodziło walidację i kończyło się 500.

### Zawieszone konta

ADR 0026 ukrył treści zawieszonego konta, ale zawieszone konto nadal:

- **nadawało status „teraz gram”.** Serwerowy poll odświeża go z zapisanych tokenów Spotify, a status ręczny żyje godzinami. Filtr siedzi teraz obok przełącznika widoczności w `NowPlayingService`, więc obejmuje listę znajomych i dobór do polla. Otwarty strumień SSE (do 30 minut) sprawdza zawieszenie przy każdym pushu, jak widoczność.
- **dostawało pushe.** Telefon zawieszonego konta dzwonił o aktywności, której nie może zobaczyć ani na nią odpowiedzieć (`songly.push.deliveries{outcome="skipped-suspended"}`).
- **było wzywane mailem do kolejki zgłoszeń jako moderator.** Zawieszony moderator nie ma dostępu do kolejki, a mail wyglądał dla pozostałych jak dowód, że ktoś się sprawą zajmuje. Mail o decyzji *wobec* zawieszonego zostaje, bo informacja o sankcji należy się zawsze.

Zbiór zawieszonych jest pobierany przy każdym odczycie treści, więc dostał indeks częściowy (`V33`, `WHERE suspended_at IS NOT NULL`).

### Now-playing

- **Kasowanie konta nie zależy od Redisa.** Klucze statusu i znacznik SSE są kasowane po commicie, a błąd Redisa trafia wyłącznie do logu. Wcześniej awaria Redisa wycofywała całe usunięcie konta, czyli awaria cache'a blokowała prawo z RODO. Klucze i tak wygasają same.
- **Token Spotify odświeżony przed błędem API przetrwa rollback** (`noRollbackFor` także dla `SpotifyApiException`). Spotify rotuje refresh tokeny, więc gdy 429 albo 5xx przyszło po odświeżeniu, rollback zostawiał w bazie stary, już unieważniony token, a następny poll kończył się `invalid_grant` i skasowaniem zdrowego linku.

### Warstwa web i konfiguracja

- **Błędy rozpoznane przez framework wracają jako 4xx**: zły `Content-Type` jako 415, brak części multiparta jako 400 `MISSING_PARAMETER`, brak nagłówka albo ciasteczka jako 400 `MALFORMED_REQUEST`, naruszenie walidacji parametru metody jako 400 `VALIDATION_ERROR`. Wcześniej wpadały do łapacza `Exception` i wracały jako 500 ze stack trace'em w logu.
- **Blokada optymistyczna to 409 `CONFLICT`**, jak naruszenie ograniczenia w bazie (§6). Podwójne „cofnij lajk” to ten sam wyścig co podwójny INSERT, tylko z drugiej strony.
- **Timeouty Redisa: 1 s na polecenie, 2 s na połączenie.** Domyślne 60 s w Lettuce zamieniało każdy mechanizm fail-open w awarię. Przy Redisie, który zawiesił się zamiast odmówić połączenia, każde żądanie czekało minutę, zanim kod mógł je przepuścić, a wątki Tomcata kończyły się po chwili.
- **Pula schedulera ma 6 wątków.** Pięć zadań `@Scheduled` (poll now-playing, heartbeat SSE i trzy godzinne sprzątania) dzieliło jeden wątek z puli domyślnej. Trzy godzinne sprzątania startują razem i zostają w fazie, więc wolny przebieg wstrzymywał poll i heartbeat SSE. Jeden wątek zostaje w zapasie.

### Content, moderacja, push

- **Błąd kasowania pliku wychodzi z magazynu** (`ObjectStorageException`). `S3ObjectStorage.delete` połykał go i logował, więc wołający nie miał wyboru. Sprzątanie porzuconych zdjęć (ADR 0048) obiecywało przerwanie przebiegu po pierwszym błędzie, a przy awarii magazynu kasowało wszystkie zaległe wiersze i osierocało wszystkie pliki. Listenery `AFTER_COMMIT` (`OrphanedAvatarCleaner`, `OrphanedPostImageCleaner`) łapią wyjątek same, bo po commicie nie ma czego wycofać.
- **„Pierwsze zgłoszenie” znaczy „pierwsze oczekujące”.** Bramka ADR 0012 sprawdzała, czy post miał *jakiekolwiek* zgłoszenie, także odrzucone. Po odrzuceniu kolejka jest pusta, więc nowe zgłoszenie tego samego posta, nawet przy zupełnie nowym problemie, nie wzywało nikogo i leżało niezauważone. Odstęp między mailami (ADR 0018) dalej chroni skrzynki moderatorów przed falą.
- **Termin bana i zawieszenia ma górną granicę 36 500 dni.** Kilkaset milionów dni wypycha `expires_at` poza zakres znacznika czasu Postgresa. Kończyło się to błędem bazy, czyli 500, a po zmianie z tego ADR-u 409, które też jest kłamstwem, bo klient nie ma z czym się pogodzić. Sto lat nie ogranicza moderacji: dłuższy ban czasowy to tylko gorsza pisownia bezterminowego.
- **FCM kasuje token wyłącznie przy kodzie `UNREGISTERED`**, nie przy samym statusie 404. Zły identyfikator projektu albo adres w konfiguracji też daje 404, a wtedy każdy push odpinałby telefon swojego adresata, czyli jeden błąd konfiguracji po cichu kasowałby wszystkie urządzenia. FCM przy nieaktualnym tokenie zawsze dokłada kod `UNREGISTERED`.

## Uzasadnienie

Każda z tych zmian przywraca regułę, którą któryś wcześniejszy ADR już przyjął, więc żadna nie potrzebuje własnego rozważania alternatyw. Wspólny wzór jest jeden: reguła była sprawdzana tam, gdzie powstała, i nie objęła ścieżki, która przyszła później albo z boku. Przykłady: odstęp maili z jedną gałęzią rejestracji, ukrywanie zawieszonych z modułem content, a fail-open z wyjątkiem w Redisie, ale bez jego zawieszenia.

Każdy test dodany w tym ADR-ze został sprawdzony mutacją zgodnie z §11: po cofnięciu poprawki czerwienieje.

## Konsekwencje

- Tabela `sessions` jest większa o wiersze odwołane w ostatnich 30 dniach (maksymalny czas życia refresh tokenu). Każde odświeżenie tokenu zostawia jeden taki wiersz, więc tabela rośnie z liczbą odświeżeń z miesiąca, nie z liczbą kont. W zamian wykrywanie kradzieży działa przez cały czas życia tokenu.
- `POST_FIRST_REPORTED` w analityce liczy teraz wejścia posta do kolejki, nie posty: post zgłoszony znowu po odrzuceniu liczy się drugi raz.
- Klient, który wysyłał hasło dłuższe niż 72 bajty, dostaje 400 `VALIDATION_ERROR` zamiast 500. Takie hasło i tak nigdy nie dało się zapisać.
- Wydane access tokeny z adresem w treści działają do wygaśnięcia (15 minut). Serwer nigdy tego pola nie czytał.
- Dodając nowe zadanie `@Scheduled`, trzeba podnieść `spring.task.scheduling.pool.size`.
