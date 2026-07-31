# Songly — Architecture Blueprint

Dokument roboczy zbierający decyzje architektoniczne dla Songly. Traktuj go jako żywy dokument — aktualizuj go, gdy podejmujesz nowe decyzje, żeby za pół roku (i przy pierwszych osobach w zespole) było wiadomo *dlaczego* coś jest zrobione tak, a nie inaczej.

---

## 1. Wizja produktu

Songly to social media dla ludzi, dla których muzyka jest częścią tożsamości społecznej: co teraz słuchasz, co lubią Twoi znajomi, z kim się dopasujesz przez gust muzyczny. Trzy filary produktu:

1. **Content & social** — posty, polubienia, komentarze, udostępnianie (klasyczny social feed, ale wokół muzyki)
2. **Live presence** — widoczność tego, co znajomi słuchają teraz (Spotify na start, inne serwisy później)
3. **Discovery** — dopasowywanie po gatunkach i społeczności tematyczne

Konsekwencja dla architektury: to nie jest jedna prosta funkcja tylko rozbudowany produkt, który będzie rósł latami. Dlatego całość projektujemy pod **łatwość dodawania nowych modułów**, a nie pod maksymalną szybkość dowiezienia MVP za wszelką cenę.

---

## 2. Filozofia architektoniczna: modularny monolit

Dla startupu na tym etapie **mikroserwisy to pułapka** — dokładają koszt operacyjny (deployment, observability, sieć między serwisami, spójność danych) zanim jest jakikolwiek ruch, który by to uzasadniał. Zamiast tego:

- Jeden deployowalny artefakt (jeden Spring Boot JAR), ale **wewnętrznie podzielony na moduły domenowe** z jasnymi granicami (package-by-feature, nie package-by-layer).
- Moduły komunikują się między sobą przez **jasno zdefiniowane interfejsy (Java interfaces / Spring events)**, nigdy przez bezpośrednie grzebanie w encjach innego modułu.
- Każdy moduł ma własne repozytoria, własne DTO na granicy, własną logikę biznesową. Żaden moduł nie zwraca na zewnątrz swoich encji JPA — zawsze DTO.
- Dzięki temu, jeśli za rok jeden moduł (np. Now Playing, bo dostanie ruch 100x większy niż reszta) będzie wymagał wydzielenia do osobnego serwisu — da się to zrobić bez przepisywania całości, bo granice już istnieją w kodzie.

**Zasada praktyczna:** wydzielaj serwis dopiero gdy masz konkretny, zmierzony powód (skalowanie, zespół, inny cykl release'ów) — nie "na zapas".

---

## 3. Moduły domenowe (bounded contexts)

| Moduł | Odpowiedzialność | Kluczowe encje |
|---|---|---|
| `identity` | rejestracja, logowanie, sesje, tokeny, usuwanie konta (orkiestracja kasowania danych w pozostałych modułach, patrz §9) | User, Session |
| `profile` | dane profilowe, ulubione gatunki | Profile, Genre, UserGenre |
| `social-graph` | znajomi, zaproszenia, blokowanie | FriendRequest, Friendship |
| `content` | posty, polubienia, komentarze, udostępnienia, feed | Post, Like, Comment, Share |
| `now-playing` | integracje ze Spotify/YouTube Music, cache aktualnego statusu | NowPlayingStatus, MusicProviderLink |
| `matching` | dopasowywanie po gatunkach, rekomendacje | MatchScore |
| `community` | grupy tematyczne, członkostwo | Community, CommunityMember |
| `notification` | powiadomienia (nowy komentarz, polubienie, zaproszenie) | Notification |
| `analytics` | agregowane liczniki zdarzeń domenowych, bez PII (patrz ADR 0004) | AnalyticsEvent |

Każdy moduł to osobny pakiet Javy (`com.songly.content`, `com.songly.nowplaying` itd.), docelowo osobny moduł Maven/Gradle jeśli projekt urośnie na tyle, że warto wymusić granice kompilatorem.

**Granic pilnuje `ModuleBoundariesTest` (ArchUnit), nie sama dyscyplina.** Naruszenie granicy nie psuje żadnego innego testu — po prostu z modularnego monolitu robi się stopniowo zwykły monolit, i orientujemy się dopiero wtedy, gdy wydzielenie modułu (§10) okazuje się niewykonalne. Sześć reguł:

1. Moduł sięga do innego **wyłącznie** przez jego publiczne interfejsy (`ProfileDirectory`, `SocialGraph`) i zdarzenia domenowe. Kryterium jest strukturalne, nie listą nazw — nowy moduł dostaje tę samą dyscyplinę bez dopisywania czegokolwiek w teście.
2. Brak cykli między modułami.
3. `common` nie zna domeny (to infrastruktura *pod* nią, nie obok).
4. Encje JPA nie są `public`.
5. Repozytoria nie są `public`.
6. Kontrolery w ogóle nie widzą encji — nie mają więc jak zwrócić encji zamiast DTO.

Reguły 4 i 5 wyglądają na kosmetykę, ale to one sprawiają, że **pierwszą linią obrony jest kompilator**: skoro encje i repozytoria są package-private, złamanie granicy zwykle nawet się nie skompiluje. Bez nich wystarczyłoby dopisać `public` przed encją, żeby reguła 1 przestała cokolwiek znaczyć.

---

## 4. Stack technologiczny i uzasadnienie

| Warstwa | Wybór | Dlaczego |
|---|---|---|
| Backend | Java 21 + Spring Boot 3 | Twój wybór, dojrzały ekosystem, świetne wsparcie dla modularnego monolitu, łatwa migracja do Kotlina później (ten sam JVM/bytecode) |
| Baza główna | PostgreSQL | Relacyjne dane (użytkownicy, posty, znajomości) z silną spójnością, dobre wsparcie dla JSON gdy trzeba elastyczności |
| Cache / stan ulotny | Redis | "Co kto teraz słucha" zmienia się co kilka sekund — trzymanie tego w Postgresie zamuliłoby bazę zapisami. Redis też dobry pod przyszły pub/sub (live update feedu) |
| Media (zdjęcia, avatary) | S3-kompatybilny object storage (np. Cloudflare R2 / AWS S3) | Nie trzymaj plików binarnych w bazie relacyjnej — nigdy |
| Mobile | Kotlin Multiplatform (logika) + natywny UI | patrz sekcja 14 |
| Web (faza 3) | Next.js / React nad tym samym API | REST API jest platform-agnostic, więc web to "tylko" kolejny klient |

---

## 5. Model danych — wysoki poziom

Kluczowe relacje (bez szczegółów kolumn — to na etapie implementacji):

- `User 1—1 Profile` — rozdzielone celowo: dane logowania (wrażliwe) osobno od danych publicznych profilu
- `User N—M Genre` (przez `user_genres`) — ulubione gatunki
- `User 1—N Post`, `Post 1—N Comment`, `Post N—M User` (przez `likes`)
- `User N—M User` (przez `friendships`, kierunkowe `friend_requests` ze statusem pending/accepted/blocked)
- `User 1—1 MusicProviderLink` (na provider: Spotify, YouTube Music...) — tokeny OAuth, refresh token, provider
- `User N—M Community` (przez `community_members`)

**Zasada:** żadna tabela nie powinna być "boską tabelą" — jeśli widzisz, że jedna tabela zaczyna obsługiwać dwa różne moduły, to sygnał że granica modułu jest źle poprowadzona.

---

## 6. API — konwencje

- REST, JSON, wersjonowane od początku: `/api/v1/...` (unikniesz bólu przy pierwszym breaking change)
- Autentykacja: JWT (access token krótkożyjący + refresh token), nie sesje serwerowe — łatwiej skaluje się poziomo i naturalnie pasuje do mobile
- Każdy endpoint dokumentowany przez OpenAPI/Swagger (springdoc-openapi) — generowane automatycznie z kodu, nie ręcznie
- Paginacja wszędzie tam, gdzie zwracasz listy (feed, komentarze, znajomi) — `?page=&size=`, nigdy "zwróć wszystko"
- Błędy w spójnym formacie (kod błędu, wiadomość, timestamp) — jeden `@ControllerAdvice` na całą aplikację

---

## 7. Integracje zewnętrzne — reality check

- **Spotify**: oficjalne, pełne wsparcie. Endpoint `Get Currently Playing Track`, OAuth2 (scope `user-read-currently-playing`), brak webhooków — trzeba pollować (np. co 30–60s) i cache'ować w Redis, żeby nie zasypać Spotify zapytaniami i nie bić w rate limity.
- **Błędy providera to nie nasze błędy.** Każda nieudana odpowiedź Spotify wychodzi z `SpotifyClient` jako `SpotifyApiException` (to `ApiException`, więc 502 `MUSIC_PROVIDER_ERROR`, a nie 500 ze stack tracem w logach). Dwa przypadki mają własne zakończenie: `invalid_grant` przy odświeżaniu tokenu = użytkownik cofnął zgodę, więc **kasujemy martwy link** i zwracamy 409 `PROVIDER_REAUTHORIZATION_REQUIRED` (bez tego poller dobijałby się do tego konta co 30s w nieskończoność — dlatego `SpotifyProvider.getCurrentlyPlaying` ma `noRollbackFor`, inaczej rollback cofnąłby to kasowanie); `invalid_grant` przy wymianie kodu z redirectu to zwykły błąd żądania, czyli 400.
- **Rate limit Spotify liczy się per client ID**, czyli wspólnie dla wszystkich naszych instancji i użytkowników. Po 429 poller przerywa przebieg i zapisuje w Redisie backoff (`Retry-After` od Spotify, a jak go brak — `songly.now-playing.poll.provider-backoff`), który wycisza również pozostałe instancje. Zamiatanie wygasłych statusów działa dalej, bo nie dotyka providera.
- **YouTube Music**: brak oficjalnego publicznego API. Dostępne są tylko nieoficjalne, reverse-engineered biblioteki oparte na scrapowaniu — łamią ToS Google i mogą przestać działać bez ostrzeżenia. **Rekomendacja dla startupu: nie buduj kluczowej funkcji produktu na czymś, co może zniknąć w każdej chwili.** Na start: integracja tylko ze Spotify + ręczne "zaznacz co słuchasz" jako uniwersalny fallback dla innych serwisów (Apple Music, YouTube Music, Tidal).
- Projektuj moduł `now-playing` z myślą o wielu providerach od razu (interfejs `MusicProvider` z metodą `getCurrentlyPlaying(userId)`), nawet jeśli na start istnieje tylko `SpotifyProvider` — dodanie kolejnego providera nie powinno ruszać reszty modułu.

---

## 8. Dopasowywanie i społeczności

- MVP: podobieństwo gatunków między dwoma użytkownikami liczone prostym współczynnikiem Jaccarda (część wspólna gatunków / suma unikalnych) — tanie obliczeniowo, wystarczające żeby zweryfikować, czy funkcja w ogóle ma sens dla userów.
- Nie buduj od razu ML-owego silnika rekomendacji — to premature optimization dla produktu bez danych o zachowaniu userów. Najpierw zbierz dane (polubienia, komentarze, kogo user obserwuje), potem myśl o modelu.
- Społeczności to grupy tematyczne (np. po gatunku) — osobny moduł, bo logika członkostwa/moderacji różni się od zwykłego social graphu.
- **Posty w społecznościach**: `posts.community_id` (nullable) — `NULL` to zwykły post, wartość to post w społeczności. Celowo jedna tabela, nie osobna `community_posts`: post to ten sam byt niezależnie od miejsca publikacji, a rozdzielenie zduplikowałoby lajki, komentarze i udostępnienia razem z całą ich obsługą. Przyjęte reguły:
  - **Czyta każdy zalogowany, publikują członkowie.** Społeczności są w pełni publiczne (listowanie, podglądanie i dołączanie nie wymagają zgody właściciela), więc ukrywanie treści przed nie-członkiem byłoby ochroną pozorną — dołączenie to jedno żądanie. Wymóg członkostwa przy pisaniu sprawia tylko, że publikacja jest świadomą decyzją.
  - **Posty społecznościowe nie trafiają do feedu znajomych ani na profil autora.** Inaczej treść z niszowej społeczności wypływałaby do ludzi, którzy nigdy jej nie wybrali. Warunek `community_id IS NULL` dotyczy posta, nie udostępnienia — bez tego share przemycałby taki post do feedu.
  - **Właściciel społeczności kasuje dowolny post u siebie** — minimum moderacji, które uzasadnia istnienie tego modułu (§8). Nie rozlewa się na posty zwykłe: tam chroni wyłącznie autorstwo.
  - **Usunięcie społeczności** idzie przez `CommunityDeletedEvent`, nie przez `ON DELETE CASCADE`. Kaskada w bazie przecinałaby granicę modułów (§2) i kasowała wiersze modułu content bez jego wiedzy. Obowiązuje ta sama pułapka co przy kasowaniu konta (§9): listener musi być zwykłym `@EventListener`, bo `posts.community_id` ma FK do `communities` i sprzątanie musi wykonać się w tej samej transakcji, **przed** `DELETE`. Z tego samego powodu `deleteAccountData` **nie** używa masowego `deleteByOwnerId` — kasowanie wsadowe omija cykl życia encji i nie wysłałoby ani jednego zdarzenia.

---

## 9. Bezpieczeństwo i prywatność

- Hasła: bcrypt/argon2, nigdy plaintext ani własny hashing
- Tokeny OAuth do Spotify/innych providerów: szyfrowane w bazie (nie plaintext), traktowane jako dane wrażliwe
- **RODO**: dane o tym, czego user słucha, to dane dotyczące zachowania — traktuj je z taką samą ostrożnością jak dane osobowe. Potrzebujesz: polityki prywatności, możliwości usunięcia konta wraz z danymi, jasnej zgody na integrację z zewnętrznymi serwisami muzycznymi (OAuth consent screen to nie wystarczy prawnie).
- **Usuwanie konta** (`DELETE /api/v1/auth/me`) — zrealizowany wymóg RODO z punktu wyżej. Wymaga ponownego podania hasła w ciele żądania: sam ważny access token nie wystarcza na akcję nieodwracalną. Skutkuje twardym usunięciem danych (hard delete), nie oznaczeniem konta jako nieaktywne.
- **Zmiana hasła** (`PUT /api/v1/auth/password`) — wymaga podania obecnego hasła, tak samo jak usunięcie konta: sam ważny access token nie wystarcza, bo mógł zostać przejęty, a to jest dokładnie ta operacja, którą przejmujący chciałby wykonać. **Odwołuje wszystkie sesje, łącznie z bieżącą** — zmiana hasła, która zostawia złodziejowi ważny refresh token, jest złudzeniem bezpieczeństwa. Bieżącej sesji nie da się zresztą oszczędzić: access token nie niesie identyfikatora sesji, więc nie wiadomo, która należy do pytającego. Endpoint nie wydaje nowej pary tokenów, bo to po cichu cofałoby połowę tego skutku.
  - **Czego to nie robi:** wydane access tokeny żyją do wygaśnięcia (15 min), bo JWT jest bezstanowy z założenia (§6). Zamknięcie tego okna wymagałoby sprawdzania sesji przy każdym żądaniu, czyli oddania tej bezstanowości. Rewokacja odcina odnawianie, nie trwającą resztkę ważności — `PasswordChangeIntegrationTest` utrwala tę granicę, żeby nikt nie wziął jej za lukę.
  - **Pułapka `@Modifying`:** `revokeAllForUser` ma `flushAutomatically = true` i to jest **warunek poprawności, nie optymalizacja**. `clearAutomatically` czyści kontekst persystencji, więc bez wymuszonego flusha wyrzuciłby niezapisaną jeszcze zmianę hasła na encji `User` — endpoint zwracałby 204, sesje faktycznie by ginęły, a stare hasło dalej by działało. Błąd cichy i wyłapywalny wyłącznie testem przeciwko prawdziwej bazie.
- **Reset hasła** (`POST /api/v1/auth/password/reset-request` + `/reset`, ADR 0008) — dopóki go nie było, użytkownik, który zapomniał hasła, tracił konto bezpowrotnie. To nie była luka w wygodzie: dwa punkty niżej opisane mechanizmy (rewokacja wszystkich sesji przy zmianie hasła i przy wykryciu kradzieży tokenu) uzasadniają się zdaniem „użytkownik wraca hasłem, którego złodziej nie ma" — a to zdanie było prawdziwe wyłącznie dla ludzi pamiętających hasło. Dla reszty ochrona przed przejęciem konta kończyła się jego trwałą utratą.
  - **Żaden z dwóch endpointów nie mówi, czy konto istnieje.** 202 na nieznany adres, 202 na żądanie wyciszone odstępem, jeden kod `INVALID_PASSWORD_RESET_TOKEN` na token nieznany, wygasły i zużyty. To ta sama zasada, co przy logowaniu (§13). **Konsekwencja mniej oczywista:** błąd wysyłki maila nie może wypłynąć jako 500, bo 500 zdarzałoby się tylko dla adresów z kontem — dlatego `PasswordResetMailer` łapie wyjątek i zostawia po nim wyłącznie wpis w logu.
  - **W bazie leży sam skrót tokenu**, jak przy refresh tokenach; wyciek bazy byłby inaczej natychmiastowym przejęciem każdego konta z oczekującym resetem. **Jeden żywy token na konto** — kolejne „nie dostałem maila" nie zostawia w obiegu następnych ważnych kluczy, których użytkownik nie ma jak unieważnić. Efekt uboczny: tabela nie rośnie bez ograniczenia, więc nie potrzebuje zadania sprzątającego jak `sessions`.
  - **Reset odwołuje wszystkie sesje**, dokładnie jak zmiana hasła — robi się go po utracie kontroli nad kontem, więc sesja sprzed resetu przekreślałaby całą operację. Symetrycznie: **zmiana hasła unieważnia oczekujący link resetujący**, bo taki link zwykle zamówił ktoś obcy.
  - **Odstęp między wysyłkami per konto** (`resend-interval`) obok rate limitingu per IP. Ten drugi sam nie wystarcza: liczy per adres źródłowy, a ofiara zasypywania pocztą jest jedna — bez odstępu endpoint byłby darmowym mailerem wycelowanym w cudzą skrzynkę.
  - **Wysyłka `AFTER_COMMIT`** (przez wewnętrzne zdarzenie modułu) — odwrotnie niż listenery kasowania konta, które muszą zdążyć *przed* commitem. Mail wysłany w transakcji, która się wycofa, niesie link do tokenu, którego nigdy nie było.
  - **Kanał e-mail** to `MailSender` w `common.mail` — infrastruktura bez wiedzy o domenie (§3, reguła 3). Domyślnie `provider=log`: instancja, która przez pomyłkę wyśle prawdziwego maila, jest gorsza od takiej, która przez pomyłkę tylko go zaloguje. Na produkcji odwrotnie — `provider=smtp` bez `spring.mail.host` **wywala start**, bo instancja tylko wyglądająca, jakby wysyłała maile, to awaria niezauważalna aż do pierwszego zgłoszenia.
- **Wykrywanie ponownego użycia refresh tokenu** (`AuthService.refresh`) — rotacja tokenu (odwołanie starego przy wydaniu nowego) była od początku, ale sama z siebie daje mniej, niż się wydaje: skradziony token dawał złodziejowi ważną sesję, a prawowitemu użytkownikowi tylko ciche 401 przy następnym odświeżeniu. Nikt nie miał jak zauważyć, że doszło do kradzieży. Token po rotacji istnieje w jednym egzemplarzu i wolno go użyć raz, więc podanie **odwołanego** tokenu znaczy, że krąży kopia — nie wiadomo tylko, po której stronie. Dlatego odwołujemy **wszystkie** sesje użytkownika: złodziej traci swoją, użytkownik wraca hasłem, którego złodziej nie ma.
  - **„Odwołany" to nie to samo co „wygasły".** Wygaśnięcie to zwykły upływ czasu i nie mówi niczego o kradzieży — liczenie go jako nadużycia wylogowywałoby ze wszystkich urządzeń każdego, kto wrócił do aplikacji po miesiącu. Tak samo dwukrotny logout: to odwołanie sesji, ale nie nadużycie.
  - **Odpowiedź nieodróżnialna od zwykłego złego tokenu** (401, `INVALID_REFRESH_TOKEN`). Osobny kod podpowiadałby atakującemu, że jego kopia została wykryta. `RefreshTokenReuseDetectedException` dziedziczy po `InvalidRefreshTokenException` właśnie po to: rozróżnienie istnieje po stronie serwera (rewokacja, log, metryka), nie w odpowiedzi.
  - **`noRollbackFor` jest warunkiem poprawności** — ta sama pułapka co w `SpotifyProvider` (§7): rewokacja dzieje się tuż przed rzuceniem wyjątku, więc bez tej flagi rollback cofa ją w całości i wykrycie kradzieży nie zostawia żadnego skutku poza logiem. Sprawdzone przez tymczasowe usunięcie flagi (§11): czerwienieją wtedy dokładnie te dwa testy, które asertują, że rewokacja przetrwała.
  - **Świadomy koszt:** klient ponawiający odświeżenie po zerwanym połączeniu wygląda tak samo jak złodziej i zostanie wylogowany ze wszystkich urządzeń. To akceptowana cena tego mechanizmu; złagodzenie go (okno tolerancji albo rewokacja tylko łańcucha, nie wszystkich sesji) wymaga lineage sesji w bazie i ma sens dopiero, gdy `songly.auth.refresh.attempts{outcome="reuse"}` pokaże, że problem jest realny.
- **Kasowanie danych między modułami**: żadna tabela nie ma FK do `users` z `ON DELETE CASCADE`, więc usunięcie samego wiersza `users` naruszyłoby integralność referencyjną. Zamiast międzymodułowego SQL-a (co złamałoby granice modułów z §2) `identity` publikuje `UserAccountDeletionRequestedEvent`, a każdy moduł sprząta własne dane w swoim `*AccountDeletionListener`. **Uwaga na pułapkę:** te listenery używają zwykłego `@EventListener`, a nie `@TransactionalEventListener(AFTER_COMMIT)` jak listenery rejestracji — muszą wykonać się synchronicznie, w tej samej transakcji, *przed* usunięciem wiersza `users`, inaczej baza odrzuci `DELETE`. Dzięki temu całość commituje się albo rolluje w całości. Dodając nowy moduł z danymi per-user, dopisz mu taki listener — brak listenera objawi się błędem FK dopiero przy pierwszej próbie usunięcia konta, dlatego `AccountDeletionFlowIntegrationTest` celowo dotyka wszystkich modułów naraz.
- **Widoczność statusu "co teraz gram"** (`GET`/`PUT /api/v1/now-playing/settings`, ADR 0007) — użytkownik może wypisać się z pokazywania statusu znajomym. Domyślnie widoczny, bo status i tak powstaje dopiero, gdy user sam podłączy serwis muzyczny albo wpisze go ręcznie — zgoda jest w tej akcji, przełącznik jest opt-outem. Ustawienie sprawdzane na **każdej** ścieżce wyjścia (lista znajomych, push SSE, dobór do polla) i przy każdym pushu z osobna, bo user może je zmienić w trakcie otwartego strumienia.
- **Blokowanie użytkownika egzekwowane w module `content`, nie tylko w `social`.** Blokada zrywała znajomość i zatrzymywała zaproszenia, ale nie zasłaniała ani jednej treści: zablokowany dalej czytał posty blokującego i dalej mógł go zaczepiać komentarzem czy lajkiem pod jego własnym postem — a każde z nich kończy się powiadomieniem. To ścieżki zapisu, nie odczyty, są prawdziwym powodem istnienia blokady. `ContentService` bierze `SocialGraph.blockedEitherWayIds(viewerId)` raz na żądanie i przepuszcza przez nie **wszystkie** wyjścia: feed, listę publiczną, oś czasu autora, posty społeczności, pojedynczy post, listę komentarzy oraz lajk/udostępnienie/komentarz.
  - **Blokada działa w obie strony** (`EitherWay`) — inaczej byłaby tylko zasłonięciem sobie oczu, a druga strona dalej śledziłaby wszystko, co piszemy.
  - **404, nie 403.** 403 potwierdzałoby, że post istnieje i że ktoś konkretny nas zablokował. To ta sama zasada, którą moduł social stosuje już do cudzych zaproszeń.
  - **Filtr siedzi w zapytaniu, nie w Javie po pobraniu strony** — odsiewanie wierszy po fakcie psułoby paginację: strona o rozmiarze 20 wracałaby z 15 wynikami, a `totalElements` liczyłby też ukryte posty. Uwaga na pułapkę SQL-a: `x not in (null)` nie jest prawdą dla **żadnego** wiersza, a pusty zbiór blokad to przypadek większości użytkowników — dlatego `BlockedContentIntegrationTest` zaczyna od testu „user bez blokad widzi posty normalnie". Bez niego naiwna implementacja wygasza feed wszystkim naraz.
  - **Cofnięcie lajka/udostępnienia blokady nie sprawdza**, choć założenie sprawdza — blokując kogoś po polubieniu jego posta trzeba móc to polubienie wycofać. Blokada odcina od cudzych treści, a nie zamraża własnych śladów na nich.
  - **Licznik komentarzy filtrujemy razem z listą** (inaczej „5 komentarzy" nad listą czterech); lajków i udostępnień **nie** — to czyste liczby bez tożsamości, więc nie da się z nich odczytać, że zablokowany tu był, ani niczego mu odpisać.
  - **Skrzynka powiadomień filtruje tak samo** (`NotificationService`) — odcięcie nowych zaczepek zostawiało te sprzed blokady w skrzynce, z nazwą, awatarem i wliczane do plakietki „nieprzeczytane". Tu jednak **ukrywamy przy odczycie, a nie kasujemy przy blokadzie**: kasowanie jest nieodwracalne, a blokada bywa zdejmowana — przy filtrze odblokowanie przywraca skrzynkę w całości. Dlatego „oznacz wszystkie jako przeczytane" też pomija ukryte: inaczej wracałyby przeczytane, mimo że nikt ich nigdy nie zobaczył, czyli blokada cicho zmieniałaby stan niewidocznych danych.
  - **Moduł `profile` zostaje poza tym celowo**: `profile` nie może zależeć od `social`, bo `social` zależy od `ProfileDirectory` — powstałby cykl, który łapie reguła 2 `ModuleBoundariesTest`. Wyszukiwarka użytkowników pokazuje więc zablokowanych dalej; zaproszenie i tak odrzuci moduł social.
- **Rate limiting na publiczne endpointy** od pierwszego dnia — proste social media są częstym celem botów zakładających konta. `RateLimitingFilter` (fixed-window w Redis, przed `JwtAuthenticationFilter`) obejmuje **wszystkie** ścieżki wystawione jako `permitAll` w `SecurityConfig`: rejestrację, logowanie, `/refresh`, `/logout` i publiczny callback OAuth. Lista to wzorce `PathPattern`, nie dosłowne URI, więc objęcie kolejnej ścieżki to zmiana w konfiguracji, nie w kodzie. Licznik jest kluczowany **wzorcem**, nie konkretnym URI — inaczej wzorzec z gwiazdką dawałby każdej ścieżce własne okno.
  - **Świadomie fail-open:** gdy Redis nie odpowiada, żądanie przechodzi (z warnem w logach), zamiast kończyć się błędem. Rate limiting jest zabezpieczeniem, nie warunkiem poprawności — awaria cache'a nie może odciąć wszystkim logowania.
  - **429 niesie `Retry-After`** z realnym TTL okna, żeby klient nie dobijał się w kółko.
  - **IP klienta bierzemy wyłącznie z `getRemoteAddr()`.** Za proxy prawdziwy adres podstawia `server.forward-headers-strategy=framework` (na Render ustawione w `render.yaml`). Ręczne czytanie `X-Forwarded-For` byłoby dziurą: bez zaufanego proxy przed aplikacją to zwykłe pole od klienta, czyli darmowe obejście limitu przez podstawianie losowych adresów.
  - Endpointy za JWT zostają poza listą — chroni je konieczność posiadania ważnego tokenu, a ich sensowne limitowanie wymaga licznika **per użytkownik**, nie per IP; filtr działa przed uwierzytelnieniem, więc na tym etapie nie wie jeszcze, kto pyta. To osobna robota, gdy pojawi się realny powód.
- **Sprzątanie sesji** (`SessionCleanupJob`) — tabela `sessions` rosła bez żadnego górnego ograniczenia: każde logowanie wstawia wiersz, a każde odświeżenie tokenu wstawia kolejny i revoke'uje poprzedni, więc przy access tokenie żyjącym 15 minut aktywny użytkownik zostawiał ~4 martwe wiersze na godzinę. To nie był problem poprawności (martwy wiersz nikogo nie wpuści), tylko rosnący koszt każdego zapytania i backupu. Zadanie kasuje sesje odwołane lub wygasłe dawniej niż `retention` (domyślnie 7 dni — okno na diagnozę „dlaczego mnie wylogowało", nie wymóg funkcjonalny). Kolumna `revoked_at` jest tu niezbędna: odwołany wiersz zachowuje swoje pierwotne `expires_at` (nawet +30 dni), więc bez niej czekałby na skasowanie trzy tygodnie dłużej, niż jest do czegokolwiek potrzebny. Kasowanie idzie **porcjami, każda w osobnej transakcji** (`@Transactional` na metodzie repozytorium, nie na jobie — inaczej wszystko commitowałoby się naraz na końcu i dzielenie na porcje nie dawałoby niczego).
- Autoryzacja: pierwsza rola w systemie to `is_admin` (boolean na `User`, patrz ADR 0006) — sprawdzana na żywo z bazy przy każdym requeście w `JwtAuthenticationFilter`, gating na poziomie `requestMatchers(...).hasAuthority(...)` w `SecurityConfig`. Brak samoobsługowego nadawania sobie roli; promocja to na razie ręczna operacja w bazie, do czasu aż powstanie panel admina.

---

## 10. Skalowanie: od monolitu do (ewentualnych) mikroserwisów

Kolejność, w jakiej realnie rosną tego typu produkty:

1. **Modularny monolit** (tu jesteś teraz) — jeden deployment, wewnętrzne granice modułów
2. **Monolit + wydzielone zasoby** — osobna baza read-replica dla ciężkich odczytów (feed), CDN dla mediów, Redis cluster
3. **Wydzielenie pojedynczych modułów do serwisów** — dopiero gdy konkretny moduł ma inne wymagania skalowania (np. `now-playing` dostaje 100x ruchu bo integracja poszła viralowo) albo inny zespół go rozwija
4. **Event-driven między serwisami** — Kafka/RabbitMQ zamiast wywołań REST między serwisami, żeby uniknąć kruchych zależności czasowych

Nie planuj kroku 3 i 4 szczegółowo teraz — tylko zostaw sobie furtkę (dobre granice modułów z punktu 2 są tą furtką).

---

## 11. Jakość kodu, testy, CI/CD

- **Testy**: piramida — dużo unit testów (JUnit 5 + Mockito) dla logiki biznesowej, testy integracyjne z Testcontainers (prawdziwy Postgres/Redis w kontenerze, nie H2 — H2 kłamie o zachowaniu Postgresa)
- **CI**: GitHub Actions — na każdy PR: build, testy, linter (Spotless/Checkstyle). Nic nie wchodzi do main bez zielonego CI.
- **Testy architektury**: `ModuleBoundariesTest` (ArchUnit) wymusza granice modułów z §2. Dopisując regułę architektoniczną, **sprawdź najpierw, że potrafi spaść** — wstaw tymczasowe naruszenie i zobacz czerwone. Reguła, która przechodzi zawsze, jest gorsza niż jej brak: daje fałszywe poczucie, że granice są pilnowane.
- **Code review**: nawet solo — rób PR-y do siebie i czytaj diff następnego dnia na świeżo. Zaskakująco skuteczne.
- **ADR (Architecture Decision Records)**: krótki plik markdown na każdą ważną decyzję ("dlaczego Postgres a nie Mongo", "dlaczego JWT a nie sesje") w `/docs/adr/`. Bezcenne gdy dołączy ktoś nowy albo gdy Ty sam zapomnisz za rok, dlaczego coś zrobiłeś tak a nie inaczej.

---

## 12. Struktura repozytorium (na start)

```
songly/
├── backend/                  # Spring Boot, modularny monolit
│   └── src/main/java/com/songly/{identity,profile,social,content,nowplaying,matching,community,notification}/
├── mobile/
│   ├── shared/                # moduł KMP: modele, sieć, logika biznesowa
│   ├── androidApp/
│   └── iosApp/
├── web/                       # faza 3, osobny frontend nad tym samym API
├── docs/
│   └── adr/
└── infra/                     # docker-compose, skrypty deploy
```

Jedno repo (monorepo) na start — łatwiej ogarnąć jako jedna osoba/mały zespół. Rozbicie na wiele repo ma sens dopiero przy większym zespole albo osobnych cyklach release'ów.

---

## 13. Infrastruktura i środowiska

- Lokalnie: `docker-compose` z Postgres + Redis, żeby środowisko dev odpalało się jedną komendą
- Środowiska: `dev` (lokalnie) → `staging` → `prod`, sekrety w zmiennych środowiskowych / vault, nigdy w repo
- Na start (koszt ma znaczenie dla startupu): Railway / Render / Fly.io — łatwy deployment, managed Postgres, płacisz za realne użycie. Migracja do AWS/GCP ma sens dopiero przy realnym ruchu i zespole DevOps.
- Observability od początku: structured logging (JSON), Micrometer + Prometheus/Grafana do metryk — dużo łatwiej dodać to na starcie niż domontowywać do rozrośniętego systemu.
- **Korelacja żądań** (`RequestIdFilter`) — każde żądanie dostaje identyfikator, który trafia do MDC, a stamtąd do **każdej** linii logu jako pole `requestId` w JSON-ie ECS. Bez tego structured logging jest tylko ładniej sformatowanym szumem: przy kilku żądaniach naraz nie da się powiedzieć, które linie należą do tego samego. Identyfikator wraca w nagłówku `X-Request-Id`, więc użytkownik zgłaszający błąd podaje klucz, którym znajdujemy dokładnie jego żądanie. Przychodzący nagłówek honorujemy (korelacja z klientem mobilnym), ale **sanityzujemy i przycinamy** — to wartość spod kontroli klienta lądująca w logach, więc bez filtra znaków dałoby się wstrzykiwać do nich dowolną treść, w tym fałszywe wpisy. Filtr jest pierwszy w łańcuchu (`HIGHEST_PRECEDENCE`), żeby odrzucenia rate limitera i nieudane uwierzytelnienia też miały identyfikator — to właśnie te żądania najczęściej się bada. `JwtAuthenticationFilter` dokłada `userId` po uwierzytelnieniu; sprzątanie MDC należy do `RequestIdFilter`, bo wątki wracają z puli do kolejnych żądań.
- **Ślad audytowy zdarzeń bezpieczeństwa**: nieudane i udane logowanie, usunięcie konta (nieodwracalna operacja RODO), żądania z uprawnieniami administratora, odrzucenia rate limitera. Wcześniej brute-force na konkretne konto nie zostawiał **żadnego** śladu — rate limiter go hamował, ale nikt nie mógł zauważyć, że miał miejsce.
  - **Bez adresów e-mail w logach.** Identyfikujemy po `userId`, a przy nieistniejącym koncie nie logujemy niczego, co by je opisywało. Logi krążą po systemach, do których RODO stosuje się tak samo jak do bazy (§9), a do zbadania ataku wystarczy licznik i `requestId`.
- **Metryki własne** uzupełniają te, które Boot daje sam (`http.server.requests` pokrywa już latencję i statusy per endpoint, więc ich nie dublujemy): `songly.auth.login.attempts{outcome}`, `songly.auth.refresh.attempts{outcome}` (seria `outcome="reuse"` w zdrowym ruchu stoi na zerze — każdy przyrost to sygnał kradzieży tokenu, patrz §9), `songly.auth.password-reset.attempts{outcome}` (ADR 0008), `songly.rate.limit.rejected{path}`, `songly.domain.events{type}` (ADR 0004) i `songly.nowplaying.*` (ADR 0007). Licznik odrzuceń tagujemy **wzorcem ścieżki, nie URI** — tag o nieograniczonej liczbie wartości rozsadza magazyn Prometheusa.
  - Sukces i porażka logowania zliczają się jednym licznikiem z tagiem `outcome`, ale **nie rozdzielamy powodów porażki** (nieznane konto vs. błędne hasło) na osobne serie: to podpowiadałoby, które adresy istnieją, a do wykrycia brute-force'u wystarczy sam wzrost liczby porażek. Ta sama zasada wiąże licznik resetu hasła — stąd brak serii mówiącej, że pytano o nieistniejące konto.

---

## 14. Mobile — strategia multiplatformowa

Rekomendacja: **Kotlin Multiplatform (KMP) dla warstwy logiki biznesowej i sieci, natywny UI** (Jetpack Compose na Androidzie, SwiftUI na iOS).

Dlaczego nie "wszystko wspólne" (Compose Multiplatform dla UI) na start: mniej ryzyka, natywny UI daje pełną kontrolę nad platform-specific rzeczami (powiadomienia, deep linki, integracje systemowe), a to Twój pierwszy produkt — warto zoptymalizować pod szybkość nauki i debugowania, nie pod maksymalne code sharing. Gdy MVP się sprawdzi i będziesz dodawać kolejne ekrany szybciej niż nadążasz, Compose Multiplatform dla UI to naturalny kolejny krok (jest już stabilny i produkcyjny, więc migracja będzie miała sens techniczny, nie tylko teoretyczny).

Backend w Javie + mobile w Kotlinie to ten sam ekosystem JVM — możesz np. dzielić reguły walidacji czy modele DTO między backendem a modułem `shared` w KMP, jeśli zorganizujesz to jako osobną bibliotekę.

---

## 15. Roadmapa fazowa

**Faza 0 — fundament (2–4 tygodnie)**
Szkielet backendu (modularny monolit), autentykacja, CI/CD, docker-compose dev environment, ADR-y dla pierwszych dużych decyzji.

**Faza 1 — MVP**
Profil + ulubione gatunki, posty + polubienia + komentarze, znajomi, integracja tylko ze Spotify, prosty feed.

**Faza 2 — growth (ukończona)**
Dopasowywanie po gatunkach, społeczności, powiadomienia, udostępnianie, podstawowa analityka produktowa (żeby wiedzieć co realnie działa) — wszystkie punkty zaimplementowane.

**Faza 3 — skala (w toku)**
- ✅ Ręczny fallback "co teraz słucham" dla serwisów bez oficjalnego API (ADR 0005) — świadomie zamiast integracji z nieoficjalnymi/reverse-engineered API (Claude.md §7).
- ⏸️ Integracja z kolejnymi serwisami muzycznymi przez *oficjalne* API — odłożone, dopóki takie API nie pojawi się dla YouTube Music/Apple Music/Tidal.
- ⏸️ Bardziej zaawansowane rekomendacje na bazie realnych danych o zachowaniu userów — odłożone celowo (Claude.md §8): najpierw trzeba zebrać dane zachowań (mamy już analytics, ale nie per-user), potem myśleć o modelu.
- ⏸️ Aplikacja webowa — poza zakresem tego repo (backend); do podjęcia gdy zacznie się prace nad `web/`.
- ⏸️ Wydzielanie modułów do serwisów — celowo nie planowane szczegółowo teraz (Claude.md §10), dopóki konkretny moduł nie pokaże zmierzonej potrzeby skalowania.

---

## 16. Rekomendowane pierwsze kroki

1. Załóż repo z powyższą strukturą + `docker-compose.yml` (Postgres + Redis)
2. Napisz pierwsze 2–3 ADR-y (wybór Postgresa, modularny monolit, JWT) — to zajmuje 15 minut na decyzję i oszczędza godziny dyskusji z samym sobą za pół roku
3. Skonfiguruj CI (nawet minimalny — build + testy) zanim napiszesz pierwszą linijkę logiki biznesowej
4. Zacznij od modułu `identity` + `profile` — wszystko inne od tego zależy
