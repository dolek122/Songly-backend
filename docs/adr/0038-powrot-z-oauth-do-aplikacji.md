# 38. Powrót z OAuth Spotify do aplikacji

Data: 2026-10-18
Status: zaakceptowane, częściowo zastąpione przez ADR 0050

## Kontekst

Połączenie ze Spotify kończy się w przeglądarce: aplikacja otwiera `authorizeUrl`, użytkownik wyraża zgodę, a Spotify przekierowuje przeglądarkę na nasz publiczny `GET /api/v1/now-playing/spotify/callback`. Callback zwracał `204`, a przy błędzie JSON z §6.

Dla przeglądarki oba wyniki są ślepą uliczką. Po udanym połączeniu użytkownik zostawał na pustej karcie i sam musiał wrócić do aplikacji, a ta nie wiedziała, że coś się zmieniło. Błąd kończył się surowym JSON-em, którego nikt poza programistą nie przeczyta. Odmowa zgody była najgorsza: Spotify wraca wtedy z `?error=access_denied&state=…` bez `code`, więc wymagany parametr dawał `400 MISSING_PARAMETER`, a do tego state wisiał w Redisie do końca TTL.

## Decyzja

Callback **zawsze odpowiada `302`** na `songly.now-playing.connect-result-url` (domyślnie `songly://now-playing/spotify`, zmienna `SPOTIFY_CONNECT_RESULT_URL`) z wynikiem w parametrze:

| Sytuacja | Przekierowanie |
|---|---|
| połączono | `?result=connected` (od ADR 0050: `?result=authorized&ticket=…`, a łączy dopiero aplikacja) |
| użytkownik odmówił (`error=access_denied`) | `?result=denied` |
| inny błąd zgłoszony przez providera | `?result=error&code=MUSIC_PROVIDER_ERROR` |
| błąd po naszej stronie (`ApiException`) | `?result=error&code=<kod z ApiException>` |

`code` stał się opcjonalny. Powrót bez kodu zużywa state (`abandonConnect`). Jedynym przypadkiem z JSON-em zostaje brak `state`: takie żądanie nie jest powrotem od providera, więc nie ma komu go odsyłać.

## Uzasadnienie

- **Odbiorcą jest przeglądarka, nie klient API.** Spójny format błędów z §6 obowiązuje klientów API. Tutaj klientem jest człowiek w przeglądarce, a jedyne, co może z tym wynikiem zrobić, to wrócić do aplikacji.
- **Kod błędu w adresie jest tym samym kodem, który był w JSON-ie.** Nie ujawnia niczego nowego, a aplikacja może rozróżnić „spróbuj jeszcze raz” od „Spotify ma awarię”.
- **Odmowa to osobny wynik, nie błąd.** Użytkownik, który kliknął „Anuluj”, nie powinien dostać komunikatu o awarii.
- **Adres w konfiguracji**, jak linki z maili (ADR 0037): przejście na App Links albo na stronę WWW to zmiana zmiennej, bez zmian w kodzie.

## Konsekwencje

- **Przechwycenie schematu przez obcą aplikację jest tu nieszkodliwe**, inaczej niż w ADR 0037. Przekierowanie nie niesie żadnego sekretu: tokeny zostają na serwerze, a state jest już zużyty. Złośliwa aplikacja dowie się najwyżej, że ktoś właśnie połączył Spotify. **Nieaktualne od ADR 0050:** przekierowanie niesie bilet, którym aplikacja kończy łączenie.
- **Nieoczekiwany wyjątek (spoza `ApiException`) nadal kończy się 500 z JSON-em.** Przekierowywanie wszystkiego ukryłoby błąd programisty pod „spróbuj ponownie”. Takie 500 zostaje zalogowane ze stack trace’em przez `GlobalExceptionHandler`.
- **Bez zainstalowanej aplikacji przeglądarka nie ma dokąd pójść.** Callback osiąga się jednak tylko z przepływu rozpoczętego w aplikacji, więc w praktyce to sytuacja hipotetyczna. **To założenie było błędne** (ADR 0050): nic nie wiązało powrotu z osobą, która zaczęła łączenie, więc adres autoryzacji podesłany ofierze przypinał jej Spotify do konta autora linku.
- Klient mobilny musi przechwytywać `songly://now-playing` i odświeżać ekran „teraz gram” po powrocie.
