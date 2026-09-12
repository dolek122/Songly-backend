# 23. Odwołania od zawieszenia konta

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

ADR 0022 dał zawieszonemu powód i termin, ale żadnej drogi, żeby decyzję zakwestionować — i sam zapisał to jako brak do nadrobienia. Obowiązuje tu zdanie z ADR 0017: **wiedzieć, dlaczego się wyleciało, i nie móc z tym nic zrobić, to nie jest ścieżka odwoławcza, tylko lepiej opisana ściana.** Przy zawieszeniu jest to ostrzejsze niż przy banie w społeczności: ban odcina od jednej społeczności, a zawieszenie od całej usługi, więc pomyłka kosztuje wielokrotnie więcej. Administracja też nie miała żadnego sygnału zwrotnego o swoich pomyłkach.

## Decyzja

Odwołanie jako atrybuty wiersza `users` (`suspension_appeal_text`, `suspension_appealed_at`, `suspension_appeal_status`), zbudowane **świadomie jako kopia konstrukcji z ADR 0017**:

| Operacja | Endpoint | Kto |
|---|---|---|
| złożenie odwołania | `POST /api/v1/auth/me/suspension/appeal` | zawieszony (aktywne zawieszenie, jedno na zawieszenie) |
| uznanie | *istniejący* `DELETE /api/v1/admin/users/{id}/suspension` | administracja |
| odrzucenie | `POST /api/v1/admin/users/{id}/suspension/appeal/rejection` | administracja |
| kolejka odwołań | *istniejący* `GET /api/v1/admin/suspensions` | administracja |

Mail o nowym odwołaniu idzie do **wszystkich administratorów** (`AdminDirectory`), bez treści odwołania.

## Uzasadnienie

- **Ten sam kształt co ADR 0017 i to jest zaleta, nie lenistwo.** Atrybuty na wierszu, którego dotyczą; jedno odwołanie na sprawę jako strukturalny anty-spam; uznanie wyrażone istniejącym „zdejmij sankcję"; odrzucenie jako osobny endpoint; treść wyłącznie w kanale pull. Dwa różne modele odwołań w jednym systemie znaczyłyby dwa zestawy reguł do pamiętania, a tu reguły są identyczne, bo problem jest identyczny.
- **Trzeci endpoint na liście dostępnej zawieszonemu** (obok statusu i usunięcia konta). Bez wpisu w `SecurityConfig` odwołanie byłoby niedostępne dokładnie dla tych, dla których istnieje — to jest ta jedna linia, której pominięcie unieważniłoby cały ADR.
- **Jedno odwołanie na zawieszenie, niezależnie od losu pierwszego.** Kanał prowadzi od ukaranego do karzącego, więc bez ograniczenia byłby narzędziem nękania administracji. Odrzucenie jest więc ostateczne dla tego zawieszenia; świeże prawo daje **nowe** zawieszenie.
- **Nowe zawieszenie czyści odwołanie, ale poprawka trwającego — nie.** To rozróżnienie, którego ADR 0017 nie musiał robić (ban aktywny jest tam nietykalny), a tutaj jest konieczne, bo `suspend` nadpisuje trwające zawieszenie (ADR 0022). Wyczyszczenie pól przy samej zmianie powodu **skasowałoby po cichu złożoną petycję** — czyli najgorszy możliwy błąd w tym przepływie. Stąd `resetAppeal()` wołany wyłącznie przy przejściu konto aktywne → zawieszone.
- **Uznanie odwołania zastępuje powiadomienie „zawieszenie zdjęte", nie dokłada się do niego.** Zdjęcie zawieszenia komuś z wiszącym odwołaniem to jedna decyzja, więc i jedna wiadomość — ta, która mówi *dlaczego* konto wróciło. Dwa maile o tym samym zdarzeniu byłyby szumem.
- **Mail do administratorów jest tu konieczniejszy niż przy zgłoszeniach społecznościowych.** Tam moderator mógł sam zajrzeć do kolejki; tu odwołanie przychodzi od użytkownika, a administracja nie ma w aplikacji żadnej skrzynki — bez maila nikt nie ma skąd o sprawie wiedzieć. Bez odstępu wyciszającego (ADR 0018): jedno odwołanie na zawieszenie plus ręcznie nakładane zawieszenia ograniczają wolumen **strukturalnie**, nie czasowo.
- **`AdminDirectory` jako nowa granica modułu** — notification musi znać adresatów, ale nie ma prawa czytać `users` (§2). Zwraca identyfikatory, jak `CommunityMembership.moderatorIds`.
- **Osobny enum `SuspensionAppealStatus`**, mimo że `BanAppealStatus` ma identyczny kształt: `community` zależy od `identity`, więc zależność w drugą stronę dałaby cykl łapany regułą 2 `ModuleBoundariesTest`. To jedyny powód tego powtórzenia i warto go zapisać, żeby nikt go „nie posprzątał".
- **Treść odwołania nie opuszcza kanału pull** (wykaz zawieszeń), mail mówi tylko, że jest po co zajrzeć. Symetrycznie do powodu zawieszenia, który też nie jedzie mailem (ADR 0015/0022) — w jedną stronę administracja nie doręcza tekstu ukaranemu, w drugą ukarany nie doręcza tekstu administracji.

## Konsekwencje

- **Migracja V22 addytywna**, trzy nullable kolumny, zero backfillu.
- **Pętla sankcji platformowych jest domknięta tak samo jak społecznościowa**: decyzja → informacja (0022) → zrozumienie (powód i termin) → odpowiedź (0023). Dokładnie ta sama ścieżka, którą ADR 0012→0017 przeszedł dla społeczności.
- **Nadal nie ma drugiej instancji.** Odrzucenie przez administratora jest ostateczne, bo nad administracją nie ma nikogo — i tym razem to nie jest luka do nadrobienia, tylko koniec drabiny.
- **Brak historii odwołań** (widać stan bieżącej sprawy, nie ile razy ktoś się odwoływał) — ten sam dług co przy banach, do zrobienia razem z historią moderacji, jeśli kiedyś będzie potrzebna.
- **Administracja nie odpowiada tekstem** — odrzucenie niesie fakt, nie uzasadnienie. Pole na odpowiedź byłoby kolejnym kanałem tekstowym w stronę ukaranego, z tym samym problemem doręczania co powód zawieszenia.
- **Pułapka testowa, która tu zadziałała:** pierwszy przebieg tej klasy padł w pełnej suicie (a przechodził sam), bo adresy `appeal-*@songly.io` kolidowały z `BanAppealIntegrationTest` — dokładnie ten scenariusz, przed którym ostrzega javadoc `registerAndVerify`. Objaw jest mylący (asercja o treści maila weryfikacyjnego), więc ostrzeżenie zostaje tam, gdzie jest.
