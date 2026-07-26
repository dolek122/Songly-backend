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

---

## 9. Bezpieczeństwo i prywatność

- Hasła: bcrypt/argon2, nigdy plaintext ani własny hashing
- Tokeny OAuth do Spotify/innych providerów: szyfrowane w bazie (nie plaintext), traktowane jako dane wrażliwe
- **RODO**: dane o tym, czego user słucha, to dane dotyczące zachowania — traktuj je z taką samą ostrożnością jak dane osobowe. Potrzebujesz: polityki prywatności, możliwości usunięcia konta wraz z danymi, jasnej zgody na integrację z zewnętrznymi serwisami muzycznymi (OAuth consent screen to nie wystarczy prawnie).
- **Usuwanie konta** (`DELETE /api/v1/auth/me`) — zrealizowany wymóg RODO z punktu wyżej. Wymaga ponownego podania hasła w ciele żądania: sam ważny access token nie wystarcza na akcję nieodwracalną. Skutkuje twardym usunięciem danych (hard delete), nie oznaczeniem konta jako nieaktywne.
- **Kasowanie danych między modułami**: żadna tabela nie ma FK do `users` z `ON DELETE CASCADE`, więc usunięcie samego wiersza `users` naruszyłoby integralność referencyjną. Zamiast międzymodułowego SQL-a (co złamałoby granice modułów z §2) `identity` publikuje `UserAccountDeletionRequestedEvent`, a każdy moduł sprząta własne dane w swoim `*AccountDeletionListener`. **Uwaga na pułapkę:** te listenery używają zwykłego `@EventListener`, a nie `@TransactionalEventListener(AFTER_COMMIT)` jak listenery rejestracji — muszą wykonać się synchronicznie, w tej samej transakcji, *przed* usunięciem wiersza `users`, inaczej baza odrzuci `DELETE`. Dzięki temu całość commituje się albo rolluje w całości. Dodając nowy moduł z danymi per-user, dopisz mu taki listener — brak listenera objawi się błędem FK dopiero przy pierwszej próbie usunięcia konta, dlatego `AccountDeletionFlowIntegrationTest` celowo dotyka wszystkich modułów naraz.
- **Widoczność statusu "co teraz gram"** (`GET`/`PUT /api/v1/now-playing/settings`, ADR 0007) — użytkownik może wypisać się z pokazywania statusu znajomym. Domyślnie widoczny, bo status i tak powstaje dopiero, gdy user sam podłączy serwis muzyczny albo wpisze go ręcznie — zgoda jest w tej akcji, przełącznik jest opt-outem. Ustawienie sprawdzane na **każdej** ścieżce wyjścia (lista znajomych, push SSE, dobór do polla) i przy każdym pushu z osobna, bo user może je zmienić w trakcie otwartego strumienia.
- Rate limiting na publiczne endpointy (szczególnie rejestrację i logowanie) od pierwszego dnia — proste social media są częstym celem botów zakładających konta.
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
