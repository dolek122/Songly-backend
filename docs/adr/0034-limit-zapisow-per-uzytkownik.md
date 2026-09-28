# 34. Limit zapisów per użytkownik

Data: 2026-09-27
Status: zaakceptowane

## Kontekst

`RateLimitingFilter` (Claude.md §9) chroni wyłącznie ścieżki publiczne i liczy per IP, bo działa przed uwierzytelnieniem. Endpointy za JWT zostały świadomie poza nim, z zastrzeżeniem: *„ich sensowne limitowanie wymaga licznika per użytkownik […] to osobna robota, gdy pojawi się realny powód"*.

Powód jest, i to nie hipotetyczny. Ścieżki zapisu kończą się powiadomieniem u **innej osoby**, a jedyna bramka per osoba w całym systemie dotyczy zgłoszeń (ADR 0018). Konkretnie:

- **lajk jest idempotentny tylko w obrębie jednego stanu** — `likePost` pomija zapis przy istniejącym lajku, ale para `DELETE`/`PUT` w pętli za każdym razem tworzy nowy wiersz, nowe `PostLikedEvent` i nowe `POST_LIKED` w skrzynce autora. To samo z udostępnieniem;
- **komentarze** nie mają żadnego ograniczenia — każdy to powiadomienie u autora posta;
- **zaproszenie do znajomych** można wysłać i anulować w pętli, a każde wysłanie to `FRIEND_REQUEST_RECEIVED`.

Blokada (§9) to zatrzymuje, ale dopiero wtedy, gdy ofiara zdąży ją założyć, a skrzynka jest już zasypana. Ważny token daje dostęp do każdej ścieżki bez ograniczenia tempa, więc skrypt z jednym kontem jest w stanie wygenerować tysiące powiadomień na minutę.

## Decyzja

`UserRateLimitingFilter` w `common.web` stoi w łańcuchu **za** `JwtAuthenticationFilter`, a **przed** autoryzacją. Kluczuje tożsamością z kontekstu bezpieczeństwa (`Authentication.getName()`), więc nie wie nic o domenie. Liczy fixed-window w Redisie, tak samo jak limit per IP. Mechanika okna i fail-open zostały wydzielone do wspólnego `FixedWindowCounter`, żeby oba limitery nie mogły się rozjechać.

Reguła to **nazwana grupa** ścieżek i metod z jednym licznikiem (`songly.security.user-rate-limit.rules`). Domyślne reguły:

| Reguła | Metody | Ścieżki | Limit |
|---|---|---|---|
| `writes` | POST, PUT, PATCH, DELETE | `/api/**` | 60 / 1 min |
| `interactions` | PUT, POST | lajki, udostępnienia, komentarze, zaproszenia do znajomych | 40 / 10 min |

Żądanie liczy się do każdej reguły, do której pasuje, i jest odrzucane po przekroczeniu którejkolwiek. Odpowiedź to 429 `RATE_LIMIT_EXCEEDED` z `Retry-After`, w tym samym formacie co przy limicie per IP. Przy kilku przekroczonych regułach `Retry-After` wskazuje najpóźniejsze okno.

## Uzasadnienie

- **Licznik per reguła, nie per ścieżka.** Ofiarę obchodzi liczba powiadomień, a nie to, przez który endpoint przyszły. Limit per ścieżka dawałby osobny budżet na lajki, komentarze i zaproszenia, czyli rozłożenie zasypywania na cztery ścieżki mnożyłoby go czterokrotnie. Z tego samego powodu licznik limitu per IP jest kluczowany wzorcem, a nie URI (§9).
- **Budżet `writes` obejmuje cały prefiks**, a nie listę endpointów. Nowy endpoint jest wtedy limitowany domyślnie, zamiast czekać, aż ktoś go dopisze. Ta sama zasada co przy `/api/v1/admin/**` (ADR 0021) i `ROLE_USER` dla zawieszonych (ADR 0022): ochrona, o której trzeba pamiętać, wcześniej czy później zostaje zapomniana.
- **Odczyty nie są limitowane.** Szkodą, przed którą się bronimy, jest wolumen u **innej** osoby, a odczyt nikomu niczego nie dostarcza. Limitowanie odczytów ma sens przy scrapingu i kosztach, ale to inny problem z innymi progami (feed odświeżany przez aplikację mobilną szybko zjadłby budżet ustawiony pod zapisy).
- **Reguła `interactions` liczy tylko kierunek „zaczepiający”** (`PUT` lajka, a nie `DELETE`). Cofnięcie własnego śladu nie może zależeć od limitu, tak jak nie zależy od blokady (§9). Użytkownik, który w panice cofa lajki po zablokowaniu kogoś, nie może dostać 429. Anulowanie zaproszenia to `DELETE /friends/requests/{id}`, czyli inny wzorzec, więc też nie jest liczone.
- **Dłuższe okno dla interakcji** (10 min), bo szkoda jest wolumenowa. Przy oknie minutowym 40 zaczepek na minutę to wciąż 2400 na godzinę w jednej skrzynce.
- **Poziomy nieosiągalne ręcznie.** 60 zapisów na minutę to jeden co sekundę. 40 interakcji w 10 minut to więcej, niż człowiek przewijający feed realnie polubi. Zgodnie z logiką ADR 0018: limit przeszkadzający ludziom działającym w dobrej wierze zostanie wyłączony przy pierwszej skardze i przestanie chronić przed czymkolwiek.
- **Filtr, nie bramka w domenie**, inaczej niż limit zgłoszeń (ADR 0018). Tam limit był częścią reguły domenowej (liczony w transakcji, po `created_at`, w oknie przesuwanym) i dotyczył jednej ścieżki. Tu chodzi o ochronę przekrojową: dopisanie sprawdzenia w każdym serwisie oznaczałoby kilkanaście miejsc do zapomnienia, a zapis odrzucony przed kontrolerem nie otwiera transakcji.
- **Fail-open przy awarii Redisa**, jak limit per IP (§9). Awaria cache'a nie może zablokować wszystkim zapisów.
- **Osobna metryka** `songly.rate.limit.user.rejected{rule}` zamiast tagu na `songly.rate.limit.rejected{path}`. Prometheus wymaga tych samych kluczy tagów w obrębie jednej metryki. Tag to nazwa reguły z konfiguracji, więc liczba wartości jest ograniczona (§13). `userId` trafia do logu z MDC.

## Konsekwencje

- **Każdy zapis może teraz zwrócić 429.** Klient mobilny musi obsłużyć to globalnie („zwolnij, spróbuj za N sekund” na podstawie `Retry-After`), a nie per ekran.
- **Dwa różne 429 na ścieżce zgłoszeń:** `REPORT_RATE_LIMIT_EXCEEDED` (ADR 0018, bramka domeny) i `RATE_LIMIT_EXCEEDED` (ten filtr). To świadome: zgłoszenie dalej liczy się do budżetu `writes`, a bramka domeny zostaje, bo ma inną skalę (godzina, historia zgłaszającego).
- **Działania ochronne nie są wyłączone z budżetu `writes`** (blokada, usunięcie konta). Przy 60/min nie da się go wyczerpać ręcznie, a wyjątki na liście ścieżek to kolejne miejsce do rozjechania. Jeśli metryka pokaże odrzucenia na takich ścieżkach, wyjątek dopiszemy wtedy.
- **Pętla like/unlike jest teraz ograniczona, ale nie zamknięta.** 40 zaczepek na 10 minut to nadal kilkaset powiadomień na dobę od jednej osoby. Dokładniejsze rozwiązanie leży w domenie: jedno `POST_LIKED` na parę (aktor, post) niezależnie od liczby cykli, analogicznie do reguły „tylko pierwsze zgłoszenie” z ADR 0012. Limit to ochrona przekrojowa, deduplikacja byłaby ochroną precyzyjną. Nie zastępują się nawzajem.
- **Testy integracyjne mają limit wyłączony w klasie bazowej** (`AbstractIntegrationTest`), a włącza go wyłącznie `UserRateLimitingIntegrationTest`. Testy przepływów robią jednym kontem serie zapisów, więc limit trafiony w połowie scenariusza dawałby 429 niezwiązane z tematem testu.
- Fixed-window ma znany efekt progu (do 2× limitu na granicy okna). Dla ochrony przed skryptem to akceptowalne, a precyzyjne okno przesuwane w Redisie kosztuje sorted set na użytkownika.
