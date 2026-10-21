# Songly — backend

Backend Songly: social media dla ludzi, dla których muzyka jest częścią tożsamości.
Trzy filary produktu:

1. **Treści i relacje** — posty, polubienia, komentarze, udostępnienia, znajomi.
2. **Na żywo** — widać, czego znajomi słuchają właśnie teraz (Spotify albo status
   wpisany ręcznie).
3. **Odkrywanie** — dopasowywanie ludzi po gatunkach i społeczności tematyczne.

REST API (`/api/v1/...`) obsługuje aplikację mobilną z repo
[Songly-mobile](https://github.com/dolek122/Songly-mobile), a w przyszłości także web.

Pełny opis architektury i decyzji jest w [`CLAUDE.md`](CLAUDE.md). Uzasadnienie każdej
większej decyzji — w ADR-ach w [`docs/adr/`](docs/adr/). Ten plik mówi tylko, jak
projekt uruchomić, przetestować i wdrożyć.

## Stack

- **Java 21 + Spring Boot 4** (web, security, data JPA, data Redis, mail, actuator)
- **PostgreSQL 16** — dane główne; schemat w migracjach Flyway (`src/main/resources/db/migration`)
- **Redis 7** — stan ulotny: „teraz gram", rate limiting, cache, pub/sub dla strumienia SSE
- **Magazyn S3** — avatary i zdjęcia w postach (lokalnie MinIO, na produkcji Cloudflare R2 albo S3)
- **JWT** — krótki access token i rotowany refresh token
- **springdoc-openapi** — kontrakt API generowany z kodu
- **Testy:** JUnit 5, Testcontainers (prawdziwy Postgres, Redis i MinIO zamiast H2), ArchUnit
- **Jakość:** Spotless (formatowanie Eclipse) sprawdzany w `verify`

## Architektura w skrócie

Modularny monolit: jeden JAR, wewnątrz podzielony na moduły domenowe (pakiet
na funkcję, nie na warstwę).

```
src/main/java/com/songly/
├── identity        # rejestracja, logowanie, tokeny, role, zawieszanie kont
├── profile         # profil, ulubione gatunki
├── social          # znajomi, zaproszenia, blokady
├── content         # posty, polubienia, komentarze, udostępnienia, feed
├── nowplaying      # Spotify i status ręczny, strumień SSE „znajomi teraz"
├── matching        # dopasowywanie po gatunkach
├── community       # społeczności, członkostwo, moderacja
├── notification    # powiadomienia w aplikacji, e-mail i push
├── analytics       # liczniki zdarzeń domenowych bez danych osobowych
├── moderationlog   # historia decyzji moderacyjnych (tylko dopisywanie)
└── common          # infrastruktura bez wiedzy o domenie
```

Moduły rozmawiają ze sobą wyłącznie przez publiczne interfejsy i zdarzenia
domenowe. Granic pilnuje test `ModuleBoundariesTest` (ArchUnit), więc złamanie
granicy wywala build, zamiast po cichu zamieniać projekt w zwykły monolit.
Szczegóły są w `CLAUDE.md` §2–3.

## Uruchomienie lokalne

**Wymagania:** JDK 21 i Docker (z Docker Compose).

1. Postaw zależności — Postgres, Redis i MinIO. Kontener `minio-init` sam zakłada
   bucket `songly-media`:
   ```
   docker compose up -d
   ```

2. Uruchom aplikację:
   ```
   ./mvnw spring-boot:run
   ```
   Lokalnie nic nie trzeba konfigurować. `application.properties` ma domyślne
   wartości dla `docker-compose.yml` i deweloperskie sekrety (tylko do użytku
   lokalnego). Flyway zakłada schemat przy pierwszym starcie.

3. API działa pod `http://localhost:8080`:
   - dokumentacja (Swagger UI): http://localhost:8080/swagger-ui.html
   - health check: http://localhost:8080/actuator/health
   - konsola MinIO: http://localhost:9001 (login `songly`, hasło `songly-dev-secret`)

### Profil `local` — przeklikanie z aplikacją mobilną

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Profil zakłada dwa konta testowe, oba z hasłem `Songly123!`:

| Konto | Rola |
|---|---|
| `admin@songly.local` | administrator |
| `user@songly.local` | zwykły użytkownik |

Profil ustawia też adres zdjęć na `10.0.2.2`, żeby emulator Androida wyświetlał
avatary i zdjęcia. Emulator widzi backend pod `http://10.0.2.2:8080`.

> **Nigdy nie włączaj profilu `local` na wspólnym środowisku.** Hasła tych kont są
> w repo, więc byłby to publicznie znany dostęp do administracji.

### E-mail i push lokalnie

Domyślnie `MAIL_PROVIDER=log` i `PUSH_PROVIDER=log`. Maile (link weryfikacyjny,
reset hasła) i pushe trafiają wtedy do konsoli backendu, zamiast być wysyłane.
Link z maila skopiujesz stamtąd.

### Spotify

Bez prawdziwej aplikacji w
[Spotify Developer Dashboard](https://developer.spotify.com/dashboard) backend
działa normalnie, tylko łączenie konta Spotify się nie powiedzie. Ręczny status
„teraz gram" działa bez Spotify. Żeby podłączyć Spotify, ustaw `SPOTIFY_CLIENT_ID`,
`SPOTIFY_CLIENT_SECRET` i `SPOTIFY_REDIRECT_URI` (patrz niżej).

## Konfiguracja

Wszystkie zmienne środowiskowe z opisami są w [`.env.example`](.env.example). To
jednocześnie lista kontrolna do ustawienia na serwerze. Najważniejsze:

| Zmienna | Do czego | Lokalnie |
|---|---|---|
| `DB_*`, `REDIS_*` | Postgres i Redis | domyślne pasują do `docker-compose.yml` |
| `JWT_SECRET` | podpis tokenów, min. 32 bajty (`openssl rand -base64 48`) | domyślny deweloperski |
| `NOW_PLAYING_TOKEN_KEY` | szyfrowanie tokenów Spotify, dokładnie 32 bajty w Base64 (`openssl rand -base64 32`) | domyślny deweloperski |
| `SPOTIFY_*` | aplikacja OAuth Spotify | nieustawione = bez Spotify |
| `MAIL_PROVIDER`, `SPRING_MAIL_*`, `MAIL_FROM` | wysyłka maili (`smtp`) | `log` |
| `PUSH_PROVIDER`, `FCM_CREDENTIALS_JSON` | pushe przez Firebase (`fcm`) | `log` |
| `STORAGE_*` | magazyn plików S3/R2 | domyślne pasują do MinIO |
| `FORWARD_HEADERS_STRATEGY` | `framework` za zaufanym proxy (Render), inaczej rate limiter widzi jeden adres IP | `none` |

**Sekretów nie commitujemy.** `.env.example` zawiera wyłącznie placeholdery.

## Testy i jakość

```
./mvnw verify
```

Robi to samo co CI na każdym PR i pushu do `main`: kompilacja, testy jednostkowe
i integracyjne oraz sprawdzenie formatowania Spotless. Testy integracyjne
stawiają własne kontenery (Testcontainers), więc **muszą mieć działającego
Dockera**, ale nie potrzebują `docker compose up`.

- Formatowanie poprawia `./mvnw spotless:apply`. `verify` tylko je sprawdza i pada
  na niesformatowanym kodzie.
- Pojedynczy test: `./mvnw test -Dtest=NazwaTestu`.
- Dopisując regułę architektury do `ModuleBoundariesTest`, sprawdź najpierw, że
  potrafi spaść. Reguła, która zawsze przechodzi, daje fałszywe poczucie
  bezpieczeństwa.

## Kontrakt API (OpenAPI)

Żywy kontrakt leży w [`docs/api/openapi.json`](docs/api/openapi.json) i jest
generowany z kontrolerów, nie pisany ręcznie. `OpenApiSpecExportTest` porównuje go
z API uruchomionym w teście. Jeśli zmienisz endpoint albo DTO i nie odświeżysz
pliku, CI padnie. Odświeżenie:

```
./mvnw test -Dtest=OpenApiSpecExportTest -Dopenapi.export=true
```

Wynik trzeba zacommitować. Aplikacja mobilna trzyma kopię tego pliku
(`shared/contract/openapi.json` w Songly-mobile), a jej test pilnuje, żeby modele
nie rozjechały się z backendem. Po zmianie API odśwież więc kopię także tam.

Wszystkie błędy mają jeden format: `code`, `message`, `timestamp`, `path`. Listy są
stronicowane (`?page=&size=`).

## Wdrożenie

Staging działa na Renderze pod https://songly-backend-q7zm.onrender.com, a całe
środowisko opisuje [`render.yaml`](render.yaml):
- usługa web z `Dockerfile`,
- Postgres,
- Redis (Key Value).

Darmowy plan usypia usługę po ok. 15 minutach bez ruchu. Pierwsze żądanie po
przebudzeniu trwa wtedy 30–60 s.

Zmienne oznaczone w `render.yaml` jako `sync: false` (sekrety, Spotify, SMTP,
Firebase, magazyn plików) ustawiasz ręcznie w panelu Rendera. Kilka ustawień
celowo nie startuje bez kompletu danych, żeby awaria nie była cicha:
- `MAIL_PROVIDER=smtp` bez `SPRING_MAIL_HOST`,
- `PUSH_PROVIDER=fcm` bez `FCM_CREDENTIALS_JSON`,
- `PRODUCTION_SECRETS_REQUIRED=true` (ustawione w `render.yaml`) bez prawdziwych
  `JWT_SECRET` i `NOW_PLAYING_TOKEN_KEY` — wartości domyślne z repo są publiczne
  (ADR 0044).

### Strona usuwania konta (Google Play)

Google Play wymaga strony WWW, na której można poprosić o usunięcie konta bez
aplikacji. Serwuje ją backend: https://songly-backend-q7zm.onrender.com/account-deletion
(ADR 0043). Ten adres wpisujesz w formularzu „Bezpieczeństwo danych”. Link
w mailu potwierdzającym bierze się z `ACCOUNT_DELETION_CONFIRM_URL` (ustawionego
w `render.yaml`). Po zmianie domeny zmień oba miejsca.

## Dokumentacja

- [`CLAUDE.md`](CLAUDE.md) — blueprint architektury: moduły, bezpieczeństwo,
  prywatność (RODO), observability, roadmapa.
- [`docs/adr/`](docs/adr/) — decyzje architektoniczne, jeden plik na decyzję.
- [`docs/api/openapi.json`](docs/api/openapi.json) — kontrakt REST API.
