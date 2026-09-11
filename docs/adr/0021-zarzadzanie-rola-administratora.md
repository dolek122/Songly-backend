# 21. Zarządzanie rolą administratora

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0006 dał rolę admina (`is_admin` na `User`, sprawdzane na żywo z bazy przy każdym żądaniu) i zapisał jej jedyne brakujące ogniwo: „brak samoobsługowego nadawania sobie roli; promocja to na razie ręczna operacja w bazie, do czasu aż powstanie panel admina". Claude.md §9 powtarza to samo.

Ręczny `UPDATE` jako **procedura operacyjna** ma trzy konkretne wady, niezależne od wygody: wymaga dostępu do produkcyjnej bazy u każdego, kto ma kogokolwiek promować; nie zostawia żadnego śladu audytowego (a §13 wymaga go dla żądań z uprawnieniami administratora); i nie daje odpowiedzi na pytanie „kto dziś ma władzę" bez wejścia do bazy.

## Decyzja

Trzy endpointy pod `/api/v1/admin`, całe poddrzewo zamknięte `hasAuthority("ROLE_ADMIN")` w `SecurityConfig`:

| Operacja | Endpoint |
|---|---|
| wykaz administratorów | `GET /api/v1/admin/admins` |
| nadanie roli | `PUT /api/v1/admin/users/{userId}/admin` |
| odebranie roli | `DELETE /api/v1/admin/users/{userId}/admin` |

Do tego licznik `songly.admin.role.changes{action="granted|revoked"}` i wpis `WARN` w logu przy każdej zmianie. **Samemu sobie nie da się odebrać roli.**

## Uzasadnienie

- **Zakaz degradowania siebie jest niezmiennikiem, nie ostrożnością.** Skoro nadawać i odbierać może wyłącznie administrator, a nikt nie może zdegradować siebie, to **w systemie zawsze zostaje co najmniej jeden administrator**. Nie potrzeba więc osobnej reguły „nie można odebrać roli ostatniemu adminowi" ani liczenia adminów przy każdej degradacji: ten sam efekt wychodzi z prostszego warunku. Gdyby tego zakazu nie było, jedna pomyłka ostatniego admina oznaczałaby powrót do ręcznego SQL-a po jakikolwiek dostęp administracyjny — czyli dokładnie to, co ten ADR likwiduje. Ta sama figura, co „właściciela społeczności nie da się zdegradować, także własną ręką" (ADR 0011).
- **Pierwszy administrator nadal powstaje w bazie i to jest nieusuwalne.** Nadawać może tylko administrator, więc skądś musi wziąć się pierwszy. Ten ADR likwiduje ręczny SQL jako *procedurę*, nie jako jednorazowy bootstrap; próba obejścia tego (np. „pierwszy zarejestrowany zostaje adminem") zamieniłaby bootstrap w wyścig o uprawnienia.
- **Gating na prefiksie `/api/v1/admin/**`, nie na pojedynczych metodach.** Kolejny endpoint w tym kontrolerze jest wtedy chroniony **domyślnie**, a nie dopiero gdy ktoś pamięta o dopisaniu reguły. Zapominalstwo w drugą stronę (endpoint bez ochrony) jest dokładnie tym rodzajem błędu, którego nie widać w testach funkcjonalnych.
- **Wykaz administratorów bez paginacji — świadomie wbrew Claude.md §6.** Zbiór jest z natury mały, a celem listy jest audyt „kto ma władzę". Stronicowanie dawałoby tu możliwość przeoczenia kogoś na drugiej stronie, czyli psułoby to, po co lista istnieje.
- **Wykaz zwraca same identyfikatory, bez nazw.** `identity` nie może zależeć od `profile`, bo `profile` zależy od `identity` — cykl złapałby reguła 2 `ModuleBoundariesTest` (ta sama lekcja co w ADR 0016, gdzie test ściął przeciek enuma). Nazwy dołoży klient, tak jak przy `CommunityMembership.moderatorIds`.
- **Odebranie roli działa natychmiast i nie wymaga unieważniania sesji** — uprawnienie czytamy na żywo z bazy przy każdym żądaniu (ADR 0006), a nie z tokenu. To jest dokładnie ta własność, za którą ADR 0006 płacił zapytaniem na żądanie; tutaj się zwraca. Gdyby rola siedziała w JWT, degradacja wymagałaby rewokacji wszystkich sesji albo czekania na wygaśnięcie tokenu.
- **Ślad audytowy to `WARN` z samymi identyfikatorami.** `JwtAuthenticationFilter` loguje już *każde* żądanie administratora, ale nie mówi, **kogo** dotyczyło; bez wpisu z serwisu w logu zostawałoby „ktoś wywołał PUT na jakimś userze". Bez adresów e-mail, jak cała reszta śladu audytowego (§13).
- **Licznik zmian roli jest serią, która ma stać.** Eskalacja uprawnień jest zdarzeniem rzadkim, więc każdy przyrost jest wart zauważenia — ta sama logika co `songly.auth.refresh.attempts{outcome="reuse"}`.
- **404 na nieznane konto nie jest wyrocznią na istnienie kont** (ADR 0009): pytającym jest ktoś, kto ma już dostęp do całej analityki. Wobec osoby postronnej nic się nie zmienia.
- **Nadanie i odebranie są idempotentne** (poza zakazem wobec siebie) — administrator nie powinien dostawać błędu za to, że ktoś inny zdążył przed nim. Ta sama zasada co przy banie (ADR 0011) i zmianie roli moderatora (ADR 0016).

## Konsekwencje

- **Promocja nie wysyła powiadomienia i to jest decyzja, nie przeoczenie.** `Notification.resource_id` jest `NOT NULL`, a zmiana roli globalnej nie ma żadnego naturalnego zasobu — podstawienie tam identyfikatora konta byłoby naciąganiem modelu. Argument z ADR 0016 („odebranie roli objawia się 403-kami, czyli wygląda jak awaria") ma tu znacznie mniejszą siłę: administratorów jest dwóch–trzech i zmianę roli uzgadniają między sobą, w przeciwieństwie do moderatorów społeczności. Gdy adminów będzie więcej, to jest pierwsza rzecz do dopisania.
- **To nie jest „panel" w sensie UI ani pełny zestaw narzędzi administracyjnych.** Dochodzi wyłącznie zarządzanie rolą; nie ma podglądu konta, wyszukiwania po adresie e-mail ani globalnego bana. Wyszukiwanie po e-mailu świadomie pomijam — to nowa powierzchnia na dane wrażliwe (§9), a do promocji wystarcza `userId` z wyszukiwarki profili.
- **Druga instancja odwoławcza (ADR 0017) nadal nie istnieje.** Rola globalna jest teraz zarządzalna, ale moderacja pozostaje społecznościowa, nie globalna (Claude.md §8); dopuszczenie admina do rozstrzygania cudzych odwołań to osobna decyzja produktowa, nie konsekwencja tej zmiany.
- **Nie ma globalnego zawieszania kont.** Troll zbanowany w kilku społecznościach dalej ma konto; blokada na poziomie platformy wymagałaby stanu na `User`, egzekwowania w uwierzytelnianiu i własnej ścieżki odwoławczej — to największa pozostała luka w moderacji i temat na osobny ADR.
