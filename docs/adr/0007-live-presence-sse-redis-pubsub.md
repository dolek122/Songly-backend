# 7. Live presence w czasie rzeczywistym: SSE + Redis Pub/Sub

Data: 2026-07-24
Status: zaakceptowane (Etapy 0–1 zaimplementowane)

## Kontekst

Widoczność tego, co znajomi słuchają **teraz**, to jeden z trzech filarów produktu (Claude.md §1 — "live presence"). Obecny stan: `NowPlayingService` trzyma status w Redis z krótkim TTL (45s), a klient dowiaduje się o zmianach wyłącznie przez **polling** (`GET /api/v1/now-playing/me`). Dla własnego statusu to wystarcza, ale dla "co słuchają znajomi" polling się nie skaluje i nie daje wrażenia "na żywo":

- żeby feed presence był świeży, każdy klient musiałby pollować status wszystkich znajomych co kilka sekund — to N×M zapytań rosnących z liczbą znajomych i userów;
- większość tych zapytań nic nie zwraca (status się nie zmienił), więc to marnotrawstwo pasma i cykli po obu stronach;
- opóźnienie jest z natury równe interwałowi pollingu — "na żywo" wymaga pushu, nie odpytywania.

Claude.md §4 wprost przewiduje **Redis pub/sub** jako mechanizm live update feedu, a §10 (kolejność skalowania) każe nie wprowadzać ciężkiej infrastruktury zanim jest potrzebna. Potrzebujemy więc transportu push serwer→klient i sposobu propagacji zmian między instancjami aplikacji po horyzontalnym skalowaniu (Claude.md §10 krok 2).

## Decyzja

**Transport: Server-Sent Events (SSE), nie WebSocket.** Klient otwiera długo żyjący strumień `GET /api/v1/now-playing/stream` (uwierzytelniony JWT), a serwer pushuje zdarzenia zmiany statusu znajomych. SSE bo:

- przepływ jest jednokierunkowy serwer→klient (klient aktualizuje **swój** status osobno, przez istniejące REST-y i serwerowy polling Spotify) — dwukierunkowość WebSocketa byłaby nieużywana;
- SSE działa natywnie na obecnym stacku servletowym Springa (`SseEmitter`), bez dokładania WebSocket/STOMP ani stacku reaktywnego (Claude.md §2 — nie komplikować ponad potrzebę);
- ma wbudowany auto-reconnect i `Last-Event-ID`, prostszy protokół, lepiej przechodzi przez proxy.

**Fan-out między instancjami: Redis Pub/Sub.** Producent zmiany statusu (serwerowy poll Spotify albo `PUT /now-playing/manual`) publikuje zdarzenie na kanał Redis. Każda instancja aplikacji nasłuchuje kanału i pushuje SSE tym lokalnie podłączonym subskrybentom, którzy są **znajomymi** usera, którego status się zmienił. Dzięki temu status zmieniony na instancji A dociera do subskrybenta trzymanego przez instancję B (Claude.md §4, §10 krok 2). Reużywamy istniejący Redis (`spring-boot-starter-data-redis` już w projekcie) — zero nowej infrastruktury.

**Kto odpytuje Spotify:** wprowadzamy serwerowy, zaplanowany poll (`@Scheduled`) statusu Spotify **tylko** dla userów, których ktoś aktualnie obserwuje (mają aktywny strumień u znajomego). To przenosi polling z N klientów na jeden serwerowy harmonogram i respektuje rate limity Spotify (Claude.md §7 — pollować co 30–60s i cache'ować).

## Uzasadnienie

- **SSE vs WebSocket** to świadomy wybór prostoty pod konkretną potrzebę (presence jest jednokierunkowe). Gdy pojawi się realna potrzeba dwukierunkowa w czasie rzeczywistym (czat, wskaźniki pisania), WebSocket wróci jako osobna decyzja — ten ADR go nie zamyka, tylko nie płaci za niego teraz (Claude.md §10 — wydzielaj złożoność, gdy masz zmierzony powód).
- **Redis Pub/Sub, nie sticky sessions ani broker (Kafka/RabbitMQ):** broker to krok 4 skalowania (Claude.md §10), nieuzasadniony przy obecnym ruchu; Redis już jest i pub/sub w zupełności wystarcza do ulotnych zdarzeń presence (zgubione zdarzenie przy chwilowym rozłączeniu jest nieszkodliwe — kolejny poll i tak odświeży stan).
- **Serwerowy poll zamiast klienckiego** minimalizuje ruch do Spotify (jeden harmonogram zamiast N klientów) i centralizuje respektowanie rate limitów — dokładnie po to jest cache w Redis (Claude.md §7).
- **Reużycie Redis + cache** trzyma presence tam, gdzie już jest stan ulotny (Claude.md §4) — bez nowej tabeli w Postgresie dla danych z natury nietrwałych.

## Konsekwencje

- **Prywatność / RODO (Claude.md §9):** "co teraz słucham" to dane behawioralne. Push presence trafia **wyłącznie do zaakceptowanych znajomych** (nigdy do zablokowanych ani obcych), a użytkownik może się z tego wypisać przełącznikiem widoczności (Etap 4). Jedno i drugie egzekwowane przy każdym pushu, nie tylko przy otwarciu strumienia — i relacja, i ustawienie mogą się zmienić w trakcie trwania połączenia.
- **Uwierzytelnienie SSE:** natywny klient (Ktor na mobilce) ustawia nagłówek `Authorization` na strumieniu bez problemu. Przeglądarkowe `EventSource` nie umie nagłówków — gdy powstanie klient webowy (Claude.md §15 faza 3), trzeba będzie krótkoterminowego tokenu w query albo cookie; poza zakresem tej decyzji.
- **Cykl życia połączenia:** trzeba heartbeatu (komentarz SSE co ~15–30s), timeoutów i limitu liczby otwartych strumieni na usera — długo żyjące połączenia to nowy zasób do pilnowania. Metryki (liczba aktywnych strumieni jako `Gauge`) wpinamy w Micrometer dodany w #6.
- **Stan w pamięci instancji:** mapa "user → jego lokalne SSE `SseEmitter`y" żyje w pamięci instancji; przy restarcie klienci się rozłączają i auto-reconnectują (SSE robi to sam). To akceptowalne dla danych ulotnych.
- **Gwarancje kanału:** Redis Pub/Sub to fire-and-forget — wiadomość opublikowana w chwili, gdy instancja jest odcięta od Redis albo wstaje po restarcie, przepada bez śladu. Świadomie akceptowalne: presence jest ulotne, a kolejny przebieg polla (Etap 1b) i tak wypchnie aktualny stan; klient przy reconnekcie bierze stan początkowy z `GET /friends`. Gdyby kiedyś potrzebne były gwarancje dostarczenia, to sygnał na broker z krokiem 4 (Claude.md §10), nie na łatanie pub/suba.
- **Wygaśnięcie statusu wykrywa poll, nie Redis:** klucz statusu znika po TTL, a Redis nikogo o tym nie zawiadamia (chyba że włączy się keyspace notifications — konfiguracja serwera plus kolejny kanał do utrzymania). Zamiast tego przy każdym przebiegu polla sprawdzamy obserwowanych użytkowników pod kątem „ogłosiliśmy granie, a statusu już nie ma". Znacznik ogłoszenia (`now-playing:announced:*`, TTL dłuższy niż najdłuższy status) odróżnia wygaśnięcie od zwykłej ciszy — bez niego „przestał słuchać" leciałoby w kółko dla wszystkich, którzy nic nie grają. Dotyczy to głównie statusu ręcznego: jego właściciel nie ma providera do odpytania, więc to jedyna ścieżka, którą da się zauważyć, że skończył.
- **„Przestał słuchać" omija filtr widoczności:** zdarzenie z pustym statusem niczego nie ujawnia, a to właśnie ono chowa użytkownika przed znajomym z otwartym strumieniem. Gdyby wpadało pod ten sam filtr co zwykłe zmiany, wyłączenie widoczności zostawiłoby ostatni status na ekranie znajomego aż do jego reconnectu — czyli funkcja prywatności nie działałaby dokładnie dla tych, którzy patrzą.
- **Presence ma własne wymagania operacyjne:** wywołania do providera muszą mieć timeouty, a pula harmonogramu pomieścić wszystkie zadania `@Scheduled` naraz. Przy jednym wątku i braku timeoutu jedno zawieszone połączenie do Spotify wstrzymuje heartbeat wszystkich strumieni, więc awaria u jednego użytkownika kładzie presence całej instancji.
- **Koszt fan-outu:** każda instancja dostaje **wszystkie** zmiany statusu, także te, których nikomu u siebie nie wypchnie. Dlatego instancja bez ani jednego otwartego strumienia porzuca wiadomość, zanim zapyta o graf znajomych — inaczej ruch do bazy rósłby iloczynem instancji i zmian.
- **Rozłączenie modułów:** `now-playing` publikuje zdarzenia zmiany statusu, a warstwa strumieniująca sięga po graf znajomych z `social-graph` przez jego interfejs (nie po encje — Claude.md §2). Trzeba zaprojektować to jako zapytanie "czy A jest znajomym B" / "znajomi z aktywnym strumieniem", nie bezpośredni dostęp do tabel.

## Plan etapów

Kolejność celowo dowozi wartość produktową jak najwcześniej i odracza infrastrukturę realtime do momentu, gdy podstawa działa (Claude.md §10).

- **Etap 0 — Presence przez REST (bez realtime, natychmiastowa wartość). ✅** `GET /api/v1/now-playing/friends` — batch aktualnych statusów znajomych z cache'a Redis (respektuje blokady/znajomości). Mobilka pokazuje "co słuchają znajomi" na pollingu. Zero nowej infrastruktury, de-ryzykuje resztę i już jest użyteczne.
- **Etap 1 — Strumień SSE na jednej instancji. ✅** `GET /api/v1/now-playing/stream` (`SseEmitter`) + wewnątrzprocesowa szyna (Spring events): zmiana statusu → push do lokalnych subskrybentów-znajomych (Etap 1a). Serwerowy `@Scheduled` poll Spotify dla obserwowanych userów, czyli znajomych osób z otwartym strumieniem — zdarzenie tylko przy realnej zmianie utworu (Etap 1b). Działa end-to-end na jednym węźle.
- **Etap 2 — Fan-out przez Redis Pub/Sub (skala pozioma). ✅** Zastąpienie szyny wewnątrzprocesowej publikacją na kanał Redis + `RedisMessageListenerContainer` na każdej instancji. To odblokowuje wiele instancji (Claude.md §10 krok 2). Publikujący nie omija kanału dla siebie — jedna ścieżka dostarczania zamiast dwóch. **Czego to nie załatwia:** poll z Etapu 1b nadal biegnie niezależnie na każdej instancji, więc tego samego obserwowanego użytkownika odpytuje tyle węzłów, ilu jego znajomych ma otwarty strumień — zwielokrotniony ruch do Spotify zostaje do domknięcia w Etapie 4 (np. krótki `SET NX EX` w Redis jako lock per użytkownik).
- **Etap 3 — Klient mobilny.** Klient SSE w module `shared` (Ktor), UI presence, reconnect z backoffem, spięcie z modelami (objęte detekcją dryfu z #2).
- **Etap 4 — Prywatność i hardening. ✅** Przełącznik widoczności statusu (`GET`/`PUT /api/v1/now-playing/settings`) egzekwowany na każdej ścieżce wyjścia — lista znajomych, push SSE, a nawet dobór userów do polla — sprawdzany przy **każdym** pushu, nie tylko przy otwarciu strumienia. Do tego heartbeat, limit strumieni na usera (429 `TOO_MANY_STREAMS`), metryki (`songly.nowplaying.streams.active`, `songly.nowplaying.stream.events`), deduplikacja polla między instancjami przez lock w Redis i zdarzenie **"przestał słuchać"** (`status: null` w pushu) zamiast zgadywania końca odtwarzania z wieku `fetchedAt`.
  - **Domyślnie widoczny:** brak wiersza w `now_playing_preferences` znaczy "pokazuj znajomym". Świadomie, bo status i tak pojawia się dopiero, gdy użytkownik sam podłączy serwis muzyczny albo ustawi status ręcznie — domyślne wyłączenie sprawiłoby, że funkcja po prostu nie działa, mimo że user ją włączył. Zgoda jest w akcji podłączenia, przełącznik jest opt-outem.
  - **Domknięte po przeglądzie gotowości:** timeouty do providera i osobny wątek na heartbeat (inaczej jedno zawieszone połączenie kładło presence całej instancji), „przestał słuchać" przy wyłączeniu widoczności / odłączeniu providera / usunięciu konta oraz przy wygaśnięciu statusu z TTL.
  - **Nie zrobione: test obciążeniowy fan-outu.** Suite ma test współbieżnej rejestracji strumieni (limit dotrzymany, nic nie ginie), ale to nie jest test obciążeniowy — sensowny wymaga narzędzia (k6/Gatling) i wdrożonej instancji z wieloma węzłami, więc czeka na środowisko staging.

Każdy etap to osobny PR (albo kilka) z zielonym CI po obu stronach. Etapy 0–2 są backendowe, 3 mobilny, 4 przekrojowy. Dopiero po Etapie 2 warto rozważyć, czy presence zasługuje na wydzielenie do osobnego serwisu (Claude.md §10 krok 3) — ale to następna decyzja, nie ta.
