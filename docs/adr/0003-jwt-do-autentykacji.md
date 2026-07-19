# 3. JWT zamiast sesji serwerowych

Data: 2026-07-19
Status: zaakceptowane

## Kontekst

API Songly obsługuje aplikacje mobilne (Kotlin Multiplatform, natywny UI) oraz docelowo web (faza 3). Trzeba wybrać mechanizm autentykacji między klientami a backendem.

## Decyzja

JWT: krótkożyjący access token + refresh token, zamiast sesji trzymanych po stronie serwera.

## Uzasadnienie

- Sesje serwerowe wymagają współdzielonego stanu (sticky session albo replikowany store sesji) przy skalowaniu poziomym API — JWT jest bezstanowy, więc każdy node API może zweryfikować token samodzielnie.
- Naturalnie pasuje do mobile: klient trzyma token lokalnie, nie ma potrzeby cookie jar ani zarządzania sesją po stronie klienta.
- Krótki czas życia access tokenu ogranicza ryzyko przy przechwyceniu, a refresh token (długożyjący, możliwy do unieważnienia) daje kontrolę nad wylogowaniem/rewokacją.

## Konsekwencje

- Trzeba jawnie zaimplementować rewokację refresh tokenów (np. tabela/allowlist w Postgresie albo Redis) — w przeciwieństwie do sesji serwerowych, samego JWT-a "nie da się unieważnić" przed wygaśnięciem.
- Tokeny OAuth do zewnętrznych providerów muzycznych (Spotify) to osobny mechanizm (server-side, szyfrowane w bazie) — nie mylić z JWT-em sesji użytkownika Songly.
- Endpointy publiczne (rejestracja, logowanie) potrzebują rate limitingu od pierwszego dnia, niezależnie od wyboru JWT/sesje.
