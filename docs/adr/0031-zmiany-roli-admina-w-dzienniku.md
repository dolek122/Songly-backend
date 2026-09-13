# 31. Zmiany roli administratora w dzienniku moderacji

Data: 2026-09-13
Status: zaakceptowane

## Kontekst

ADR 0024 zbudował dziennik moderacji jako odpowiedź na pytanie „co ten człowiek ma za sobą" i objął nim wszystkie sankcje: bany w społecznościach, usunięcia postów, rozstrzygnięcia zgłoszeń, odwołania, zawieszenia kont. ADR 0021 dołożył zarządzanie rolą administratora przez API, z wpisem `WARN` w logu i licznikiem `songly.admin.role.changes{action}`.

Została z tego asymetria, której nie da się obronić: **decyzja o tym, kto ma władzę nad wszystkimi tamtymi sankcjami, jest jedyną, której historii nie da się odczytać**. Zawieszenie zwykłego konta widać w `GET /api/v1/admin/moderation-log`. Nadanie komuś uprawnień administratora — które pozwala zawieszać konta, uchylać bany w cudzych społecznościach i rozstrzygać eskalacje (ADR 0028) — zostawia ślad wyłącznie w logach aplikacji i w szeregu czasowym Prometheusa. Żeby sprawdzić, kto kogo mianował i kiedy, trzeba mieć dostęp do maszyny albo do Grafany; przez API platformy to niewidoczne.

To odwrotność tego, czego wymaga się od władzy: im poważniejsza decyzja, tym trwalszy i łatwiej dostępny ślad.

## Decyzja

`identity` publikuje `AdminRoleChangedEvent(userId, actorId, granted, occurredAt)` przy **faktycznej** zmianie roli, a `moderationlog` zapisuje z niego `PLATFORM_ADMIN_GRANTED` albo `PLATFORM_ADMIN_REVOKED`.

Wpis jest **platformowy**: `communityId` puste, dokładnie jak przy zawieszeniach (ADR 0022). Podmiotem jest ten, czyja rola się zmieniła, autorem — administrator, który to zrobił.

Log `WARN` i licznik Micrometera **zostają**.

Bez migracji: `action` to kolumna `VARCHAR(40)`, a wartości enuma są tekstem.

## Uzasadnienie

**Dlaczego mimo istniejącego logu i licznika.** Trzy ślady odpowiadają na trzy różne pytania i żaden nie zastępuje pozostałych. Log aplikacji mówi „co się dzieje **teraz**" — jest do alertowania i ginie razem z retencją logów. Licznik mówi „**ile** tego jest" — jest bez tożsamości z definicji, bo tag z identyfikatorem użytkownika rozsadziłby Prometheusa (§13). Dziennik jako jedyny mówi „**kto komu** i **kiedy**", jest trwały jak dane i czyta się go tym samym endpointem co resztę historii moderacyjnej. Dla decyzji o eskalacji uprawnień to jest właśnie ten potrzebny ślad.

**Dlaczego w tym dzienniku, a nie w osobnym audycie.** Bo pytanie „co ten człowiek ma za sobą" obejmuje także „czy miał uprawnienia i kto mu je dał". Rozdzielenie na dwa dzienniki wymagałoby od czytającego złożenia dwóch osi czasu, żeby zobaczyć rzecz najciekawszą przy nadużyciu uprawnień: że ktoś dostał rolę o 14:02, a o 14:05 zawiesił konto. Moduł `moderationlog` jest już „historią decyzji władzy" — rola administratora jest decyzją władzy o władzy.

**Dlaczego to jedyny wpis, który nie ogranicza podmiotu.** Wszystkie pozostałe akcje w tym enumie coś odbierają (ban, usunięcie posta, zawieszenie) albo rozstrzygają czyjąś petycję. Nadanie roli daje — i to jest świadome rozszerzenie znaczenia dziennika z „sankcje" na „decyzje władzy". Alternatywą byłoby pilnowanie czystości definicji kosztem niewidoczności najcięższej decyzji w systemie, czyli wymiana rzeczy ważnej na porządek nazewniczy.

**Dlaczego zdarzenie, a nie zapis wprost z `AdminRoleService`.** Bo `identity` nie może zależeć od `moderationlog` — zapis wprost przeciąłby granicę modułów i dodał zależność w stronę, w którą nikt jej nie ma (Claude.md §2). Zdarzenie to ta sama droga, którą do dziennika trafia wszystko inne.

**Dlaczego boolean `granted`, a nie dwa zdarzenia.** Kształt identyczny z `CommunityMemberRoleChangedEvent` (ADR 0016), gdzie boolean wziął się z konieczności — enum ról był wewnętrznym słownikiem `community` i reguła 1 `ModuleBoundariesTest` złapała wersję, która go wynosiła. Tu takiego przymusu nie ma (rola to `boolean` na `User`), ale skoro obie zmiany ról opisują to samo, niech mają ten sam kształt.

**Dlaczego tylko przy faktycznej zmianie.** `grant` i `revoke` są idempotentne od ADR 0021 i wychodzą wcześnie, gdy nie ma czego zmieniać — zdarzenie leci za tym `return`, więc powtórzone kliknięcie nie dokłada wpisu. Dziennik zapisuje zmiany stanu, nie kliknięcia; ta sama zasada co „tylko nowy ban" (ADR 0014) i „tylko pierwsze zgłoszenie" (ADR 0012).

## Konsekwencje

**Wpisy o roli znikają razem z kontem podmiotu**, a po skasowaniu konta administratora, który rolę nadał, zostają z pustym autorem (`clearActor`, ADR 0024). Skutek wart nazwania: po skasowaniu konta świeżo mianowanego administratora **nie zostaje ślad, że ktokolwiek go mianował**. To jest ta sama zasada, którą ADR 0024 przyjął dla sankcji („historia sankcji wobec nieistniejącego konta nie ma czego opisywać"), zastosowana konsekwentnie — ale przy uprawnieniach kosztuje więcej niż przy banach, bo kasowanie konta staje się sposobem na wyczyszczenie tej części historii. Zmiana tego wymagałaby trzymania wpisów po skasowaniu konta, czyli identyfikatora bez właściciela — dokładnie tego, czego RODO-wa ścieżka kasowania (§9) ma nie zostawiać. Zostaje log `WARN`, który znika dopiero z retencją logów.

**Powiadomienia celowo poza zakresem.** Zmiana roli moderatora społeczności powiadamia (ADR 0016), bo dotyczy kogoś, kto może o niej nie wiedzieć, a odebranie uprawnień objawiałoby się inaczej samymi 403-kami. Rola administratora platformy jest dziś uzgadniana poza systemem w grupie kilku osób, więc powiadomienie nie niosłoby informacji, której adresat nie ma. Gdyby kiedyś było potrzebne, jego miejsce jest **poza** zbiorem `MODERATION` — jak w ADR 0016, bo nadanie roli niczego adresatowi nie ogranicza.

**Analityka bez zmian.** Zdarzenie nie idzie do `analytics`, bo wolumen zmian roli już mierzy licznik z ADR 0021, a analityka odpowiada na pytania produktowe o zachowania użytkowników (ADR 0004). Dokładanie tam serii o kilku zdarzeniach rocznie dawałoby trzeci ślad tej samej rzeczy.

**Bez `expiresAt` i bez `reportCount`** — rola nie ma terminu ani licznika. To pierwszy wpis, w którym oba pola są puste z natury akcji, a nie z okoliczności (ban bezterminowy też ma puste `expiresAt`, ale mógłby mieć termin).
