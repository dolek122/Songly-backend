# 44. Odmowa startu z sekretami deweloperskimi

Data: 2026-10-21
Status: zaakceptowane

## Kontekst

`application.properties` ma domyślne wartości `JWT_SECRET` i `NOW_PLAYING_TOKEN_KEY`, żeby lokalnie nic nie trzeba było konfigurować (README). Te wartości są publiczne, bo leżą w repo.

Jeśli na serwerze zabrakło zmiennej środowiskowej, aplikacja startowała po cichu z wartością z repo. Przy `JWT_SECRET` każdy mógł wtedy podpisać token dowolnego konta, także administratora. Przy `NOW_PLAYING_TOKEN_KEY` zrzut bazy oddawał tokeny Spotify wszystkich użytkowników. Z zewnątrz nie było tego widać.

To ten sam rodzaj awarii, przed którym chroni już `MAIL_PROVIDER=smtp` bez `SPRING_MAIL_HOST`: instancja wygląda na sprawną, a nie jest (Claude.md §9).

## Decyzja

- Środowisko deklaruje `songly.security.production-secrets-required=true` (zmienna `PRODUCTION_SECRETS_REQUIRED`, na Renderze ustawiona w `render.yaml`).
- Przy `true` aplikacja nie startuje, gdy `JWT_SECRET` albo `NOW_PLAYING_TOKEN_KEY` zawiera znacznik `dev-only`, którym oznaczone są obie wartości z repo. Komunikat podaje nazwę zmiennej, nie wartość.
- Przy `true` nie startuje też profil `local`, który zakłada administratora ze znanym hasłem (§13).
- Domyślnie `false`, więc lokalny start bez konfiguracji działa jak dotąd.

## Uzasadnienie

- **Środowisko deklaruje się samo**, zamiast żeby kod zgadywał, gdzie działa. Zgadywanie po `MAIL_PROVIDER` albo `FORWARD_HEADERS_STRATEGY` wiązałoby bezpieczeństwo kluczy z niezwiązanymi ustawieniami, a te zmieniają się z innych powodów.
- **Znacznik zamiast porównania z dokładną wartością.** Porównanie dosłowne wymagałoby trzymania kopii sekretu w kodzie i przestałoby działać po pierwszej zmianie wartości domyślnej. Znacznik obejmuje każdą przyszłą wartość deweloperską oznaczoną tak samo.
- **Każdy moduł sprawdza swój sekret** (`JwtService`, `TokenEncryptor`), a `common.config.DevSecrets` daje tylko regułę. `common` nie musi znać nazw propercji modułów (§3, reguła 3).

## Konsekwencje

- Staging, na którym brakuje którejś z dwóch zmiennych, po wdrożeniu tej zmiany **nie wstanie**, a log startu powie, którą zmienną ustawić. To jest cel tej zmiany: brak sekretu przestaje być niewidoczny.
- Nowa wartość deweloperska sekretu musi zawierać `dev-only`, inaczej ta zapora jej nie zobaczy.
