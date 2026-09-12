# 28. Druga instancja odwoławcza od banu w społeczności

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

ADR 0017 zbudował odwołania od banów i zamknął je jednym zdaniem: „odrzucenie jest ostateczne i nie ma instancji odwoławczej — nad moderacją społeczności nie ma dziś nikogo (Claude.md §8). Gdy pojawi się rola globalna, to jest naturalne miejsce na drugą instancję".

Rola globalna jest zarządzalna od ADR 0021, a platforma nauczyła się orzekać we własnych sprawach (ADR 0022, 0023). Zostało więc dokładnie to, co tamten ADR zapowiedział: zbanowany, którego odwołanie odrzuciła moderacja społeczności, nie ma gdzie pójść — a moderator jest w tej sprawie jednocześnie stroną i sędzią.

## Decyzja

Trzeci szczebel istniejącego przepływu, wyrażony dwiema nowymi wartościami `BanAppealStatus` (`ESCALATED`, `ESCALATION_REJECTED`) i jedną kolumną (`appeal_escalated_at`):

| Operacja | Endpoint | Kto |
|---|---|---|
| przekazanie sprawy | `POST /communities/{id}/ban-appeal/escalation` | zbanowany, **tylko** po odmowie moderacji |
| kolejka spraw | `GET /api/v1/admin/ban-appeals` | administracja |
| **uchylenie banu** | `DELETE /api/v1/admin/communities/{id}/bans/{userId}` | administracja |
| utrzymanie banu | `POST /api/v1/admin/communities/{id}/bans/{userId}/escalation-rejection` | administracja |

## Uzasadnienie

- **Platforma wkracza wyłącznie na wniosek — i to jest tu najważniejsze.** Administracja nie przegląda cudzych banów z własnej inicjatywy: bez eskalacji nie ma czego uchylać (404). Autonomia społeczności zostaje regułą (§8), a przegląd platformy wyjątkiem, który uruchamia **sam ukarany**. Gdyby admin mógł wejść w dowolny ban, moderacja społecznościowa przestałaby być społecznościowa — a to jest filar tego produktu, nie szczegół.
- **Eskalacja tylko ze stanu „odwołanie odrzucone".** Przed orzeczeniem pierwszej instancji nie ma czego eskalować, a dopuszczenie tego omijałoby moderację społeczności, czyli instancję domyślną. Jedna eskalacja na ban wychodzi z tego samego mechanizmu co jedno odwołanie na ban (ADR 0017): ze stanu `REJECTED` wychodzi się bezpowrotnie.
- **Kolejka pokazuje obie strony sporu** — powód banu **i** treść odwołania. Administrator rozstrzyga między dwiema stronami, więc musi znać obie; to jednocześnie jedyne miejsce, w którym ktoś spoza społeczności widzi jej powody banów, i wyłącznie dla spraw skierowanych do niego przez samego zbanowanego. Kanał pozostaje **pull**: żadnych cudzych tekstów w mailu (ADR 0015).
- **Mail do administracji jest konieczny, nie dodatkowy** — administracja nie ma w aplikacji kolejki, do której zaglądałaby sama z siebie, więc bez powiadomienia sprawa leżałaby bezterminowo. Dokładnie ten sam argument, co przy odwołaniach od zawieszenia (ADR 0023).
- **Uchylenie banu zawiadamia też moderatorów.** Bez tego zbanowany po prostu wraca do społeczności, a moderacja nie ma pojęcia dlaczego — decyzja z zewnątrz wyglądałaby jak awaria, czyli dokładnie problem, od którego zaczął się ADR 0012. Powiadomienie idzie kanałem „kolejki" (odstęp z ADR 0018 obowiązuje).
- **Osobne zdarzenia od tych z pierwszej instancji**, mimo identycznego skutku. `CommunityBanAppealAcceptedEvent` znaczy „moderacja społeczności uznała odwołanie" i mail tak właśnie brzmi; przy uchyleniu z zewnątrz byłoby to po prostu nieprawdziwe. Przy okazji uchylenie publikuje też `CommunityBanLiftedEvent`, więc dziennik moderacji (ADR 0024) zapisuje zarówno *co* się stało, jak i *kto* to zrobił — a w polu autora widnieje administrator, nie moderator.
- **Rozstrzygnięcia w `MODERATION`** (ADR 0012): to odpowiedź na własną petycję, a jej nie da się odmówić — kryterium doprecyzowane w ADR 0017.
- **Kontroler administracyjny leży w module community**, bo to jego dane, a pod prefiksem `/api/v1/admin/**` rolę pilnuje `SecurityConfig` (ADR 0021). Ten sam układ co historia moderacji (ADR 0024): prefiks jest wspólny, właścicielstwo danych zostaje przy module.

## Konsekwencje

- **Drabina ma koniec i to jest deklaracja, nie brak**: nad administracją platformy nie ma nikogo, więc `ESCALATION_REJECTED` jest stanem końcowym i trzeciej instancji nie będzie. Świeże prawo do odwołania daje wyłącznie **nowy** ban (`reimpose` czyści całą historię sprawy, w tym eskalację).
- **Administracja może uchylić decyzję moderatora, ale nie może zabronić jej powtórzenia.** Nic nie stoi na przeszkodzie, żeby moderator zbanował tę samą osobę ponownie — i wtedy zaczyna się nowa sprawa z nowym prawem do odwołania. Blokada takiego zachowania wymagałaby pojęcia „ban zakazany przez platformę", czyli realnego ograniczenia autonomii społeczności; do rozważenia dopiero, gdyby to się realnie zdarzało.
- **Kolejka drugiej instancji jest globalna i bez paginacji** (jak wykazy z ADR 0021 i 0022) — eskalacji jest z natury tyle, ile spraw, które przeszły dwa szczeble. Uwaga dla testów: właśnie dlatego asercje muszą dotyczyć konkretnej sprawy, nie indeksu ani pustości listy.
- **Moderatorzy nie są pytani o zdanie przed decyzją administracji** — dostają informację po fakcie. Pytanie ich zamieniłoby przegląd w negocjację i wydłużało sprawę, w której ktoś jest odcięty od społeczności; kolejka pokazuje przy tym ich własny powód banu, więc ich stanowisko jest w aktach.
- **Zmiana wartości enuma widoczna w API** (`appealStatus` w statusie członkostwa i na liście banów) — addytywna, ale klient musi umieć wyświetlić nieznany stan, jak przy każdym poprzednim rozszerzeniu (ADR 0012).
