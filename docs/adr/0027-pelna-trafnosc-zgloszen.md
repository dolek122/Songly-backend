# 27. Pełna trafność zgłoszeń

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

Trzy ADR-y opisywały tę samą lukę z różnych stron:

- **0019**: „zgłoszenie zamknięte usunięciem posta nie zostawia śladu jako rozstrzygnięcie… `COMMUNITY_POST_REMOVED` obejmuje też posty usunięte bez żadnego zgłoszenia";
- **0024**: dziennik zapisuje, **ile** zgłoszeń post miał, ale nie **czyich**;
- **0025**: „sygnał jest jednostronny — liczymy odrzucenia, nie trafienia… do pełnej trafności trzeba by logować jeden wpis na zgłaszającego przy usunięciu posta".

Skutek był przewrotny: **historia zgłoszenia ginęła dokładnie wtedy, gdy zgłoszenie okazywało się trafne**, bo uwzględnienie zgłoszenia = usunięcie posta = kaskadowe usunięcie jego zgłoszeń. Zgłaszający, który dziesięć razy miał rację i raz się pomylił, wyglądał w systemie identycznie jak ktoś z jedną pomyłką i zerem trafień — i dostawał taką samą karę na przydziale (ADR 0025).

## Decyzja

1. **Tabela `reporter_reputation`** — liczniki `upheld`/`dismissed` per (osoba, społeczność), zwiększane w chwili decyzji, z backfillem odrzuceń przy migracji.
2. **`CommunityPostRemovedEvent` niesie `reporterIds`** zamiast samego `reportCount` (liczba nadal dostępna jako `size()`), a dziennik zapisuje obok wpisu o usunięciu jeden **`POST_REPORT_UPHELD` na zgłaszającego**.
3. **Trafienia wybaczają odrzucenia jeden za jeden** w przydziale zgłoszeń: `limit - kara × max(0, dismissed - upheld)`, nadal z podłogą i **bez premii powyżej limitu bazowego**.
4. **Kolejka pokazuje obie liczby** (`reporterUpheldCount`, `reporterDismissedCount`) w zasięgu tej społeczności.

## Uzasadnienie

- **Liczniki, nie wyliczanie z `post_reports` — i to jest sedno tej zmiany.** Dopóki trafność liczyliśmy z tabeli zgłoszeń, dane ginęły przy każdym trafnym zgłoszeniu, bo kaskada zabiera zgłoszenia razem z postem. Licznik nie jest powiązany z postem, więc przeżywa jego usunięcie. Naprawia to też lossiness zapisaną w ADR 0025 („odrzucenia, których post usunięto później, znikają razem z nim") — teraz nie znikają.
- **Klucz per (osoba, społeczność), bo te same liczby odpowiadają na dwa pytania w dwóch zasięgach.** Limit sumuje wszystkie społeczności (zasypywanie rozkłada się na wiele — ADR 0025), kolejka pokazuje wyłącznie tę jedną (moderator nie ma prawa wiedzieć, co ten człowiek robił w obcych społecznościach — §8). Jedna tabela obsługuje oba zasięgi bez duplikowania danych.
- **Backfill w migracji, nie start od zera.** Bez niego przełączenie limitu na nowe liczniki oddałoby pełny przydział każdemu, komu już odrzucono zgłoszenia — czyli po deployu troll dostałby amnestię. Odzyskujemy tyle, ile się da: odrzucenia, których post jeszcze istnieje.
- **Kompensacja jeden za jeden, a nie osobna „premia".** Formuła zostaje czytelna: liczy się **saldo** pomyłek. Dziesięć trafień i dwie pomyłki to zgłaszający bez kary; dwie pomyłki i zero trafień to przydział ścięty. Skomplikowane wagi (procent trafności, wykładniki) wymagałyby danych o rozkładzie, których nie mamy — a przy zerowym ruchu byłyby strojeniem na wyczucie.
- **Trafienia nie podnoszą przydziału powyżej bazowego.** Limit 20/h jest już hojny; premia otwierałaby szerszy kanał zasypywania komuś, kto raz zgłaszał dobrze — a konto z dobrą historią jest właśnie tym, które warto przejąć.
- **Skasowanie własnego posta nie liczy się jako trafienie.** Zdarzenie powstaje tylko przy usunięciu przez moderację (ADR 0012), więc autor spamu nie podniesie nikomu trafności jednym kliknięciem — i nie rozstrzygnie własnej sprawy.
- **Wpis w dzienniku obok, nie zamiast.** Usunięcie posta zapisujemy dwa razy, z dwóch perspektyw: jako decyzję wobec autora (z liczbą zgłoszeń) i jako rozstrzygnięcie na korzyść każdego zgłaszającego. To pierwsze odpowiada na „co się stało z tym postem", drugie na „czyje zgłoszenia bywają trafne" — jedno nie zastępuje drugiego.
- **`reporterIds` zamiast `reportCount` w zdarzeniu**, żeby nie mieć dwóch źródeł prawdy: liczba to `reporterIds().size()`.

## Konsekwencje

- **Nowa tabela per-user i per-community**, więc sprzątają ją oba istniejące listenery modułu content (kasowanie konta i usunięcie społeczności, §9). Reputacja znika razem z kontem: nowe konto to nowa tożsamość, więc trzymanie jej nie chroniłoby przed niczym.
- **Trafność jest liczona od teraz** (poza backfillem odrzuceń) — zgłoszenia trafne z przeszłości przepadły bezpowrotnie razem ze swoimi postami i nie ma ich skąd odzyskać. To dokładnie cena, którą ADR 0019 opisał, i dlatego warto było ją zapłacić raz.
- **`PostReportResponse` ma nowe pole** (`reporterUpheldCount`) — zmiana addytywna.
- **Reset historii nadal nie istnieje** (ADR 0025), ale kompensacja łagodzi problem znacznie: aktywny, trafny zgłaszający sam sobie odbudowuje przydział, więc okno czasowe na karę jest mniej potrzebne niż było.
- **Liczniki mogą się rozjechać z `post_reports`**, jeśli ktoś kiedyś zmieni status zgłoszenia poza `dismissReport`. Świadomie nie ma tu mechanizmu uzgadniania — tabela jest dziennikiem decyzji, nie widokiem materializowanym, a jedyna ścieżka zmiany statusu prowadzi przez ten serwis.
