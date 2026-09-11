# 17. Ścieżka odwoławcza od bana

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0012 zapisał w konsekwencjach: „zbanowany dostaje powiadomienie, ale nadal nie ma jak się odwołać", a ADR 0015 domknął połowę tego problemu — zbanowany zna już powód i termin. Druga połowa została: **wiedzieć, dlaczego się wyleciało, i nie móc z tym nic zrobić, to nie jest ścieżka odwoławcza, tylko lepiej opisana ściana.** Moderacja też traciła: jedyną informacją zwrotną o pomyłce był brak jakiejkolwiek informacji.

Kanał kontaktu z moderacją mógłby powstać jako osobny byt (wiadomości, tickety), ale to nowy moduł o własnych regułach spamu i prywatności. Tu potrzebna jest jedna rzecz: odpowiedź na jedną konkretną decyzję.

## Decyzja

Odwołanie jest **atrybutem wiersza bana**, nie osobną encją. Trzy kolumny na `community_bans`: `appeal_text`, `appealed_at`, `appeal_status` (`PENDING`/`REJECTED`; `NULL` = brak odwołania).

| Operacja | Endpoint | Kto |
|---|---|---|
| złożenie odwołania | `POST /communities/{id}/ban-appeal` | zbanowany (ban aktywny, jedno na ban) |
| uznanie | *istniejący* `DELETE /communities/{id}/bans/{userId}` | moderacja |
| odrzucenie | `POST /communities/{id}/bans/{userId}/appeal/rejection` | moderacja |

Trzy nowe typy powiadomień — `BAN_APPEAL_SUBMITTED` (do **wszystkich** moderatorów), `BAN_APPEAL_ACCEPTED`, `BAN_APPEAL_REJECTED` — wszystkie w zbiorze `MODERATION`, więc przechodzą mimo blokad i wychodzą mailem.

## Uzasadnienie

- **Odwołanie mieszka na banie, bo to atrybut jednej sprawy.** Klucz główny `(community_id, user_id)` mówi „jedna para — jeden ban"; odwołanie dotyczy dokładnie tego bana. Osobna tabela dawałaby historię odwołań, której nikt dziś nie czyta, i wymagałaby własnego sprzątania przy kasowaniu konta. Przy okazji: **żadnej nowej kolumny z FK do `users`** — świadomie zrezygnowaliśmy z `appeal_resolved_by`, bo kto rozstrzygnął, niesie już aktor powiadomienia, a każda taka kolumna to kolejne zerowanie przy kasowaniu konta (jak `banned_by`, ADR 0011).
- **Jedno odwołanie na ban — to strukturalny anty-spam, nie limit z wygody.** Kanał z natury prowadzi od osoby ukaranej do osoby, która ją ukarała, więc bez ograniczenia byłby narzędziem nękania moderacji. Jedno odwołanie na ban znaczy też, że **odrzucenie jest ostateczne dla tego bana**; świeże prawo daje dopiero nowy ban (`reimpose` czyści pola odwołania — nowa sprawa, nowe prawo, nawet po wcześniejszym odrzuceniu). Nie ma tu żadnego licznika ani okna czasowego: bramka wynika ze struktury danych, jak „tylko pierwsze zgłoszenie" (ADR 0012).
- **Uznanie odwołania to unban, a nie osobny endpoint.** To jedna decyzja: „ban zostaje" albo „ban znika". Osobny „endpoint uznania" musiałby i tak skasować ban, czyli robiłby to samo co `DELETE`, tylko pod inną nazwą — i tworzyłby stan „odwołanie uznane, ale ban dalej działa". Odwrotna strona tej samej reguły: `DELETE` na banie z **wiszącym** odwołaniem publikuje zdarzenie uznania, bo moderator, który zdejmuje ban wprost, właśnie odpowiedział na petycję; bez tego zostawiałby ją bez odpowiedzi, mimo że jej udzielił.
- **Tylko ban aktywny.** Wygasły ban to brak bana (ADR 0014), więc nie ma się od czego odwoływać (409 `NO_BAN_TO_APPEAL`), a odwołanie przy banie wygasłym jest bezprzedmiotowe i nie da się go odrzucić. Ta sama zasada we wszystkich trzech operacjach.
- **Treść odwołania nie opuszcza kanału pull.** Mail i powiadomienie mówią wyłącznie, że odwołanie jest; tekst czeka na liście banów, którą moderacja musi sama otworzyć. Dokładnie ta sama reguła, którą ADR 0015 zastosował do powodu bana — tylko w drugą stronę: tam moderator nie doręcza tekstu ukaranemu, tu ukarany nie doręcza tekstu moderatorom. Bez tego odwołanie byłoby darmowym kanałem dostarczania dowolnej treści do cudzej skrzynki pocztowej, i to wyzwalanym przez osobę właśnie ukaraną.
- **Wszystkie trzy typy w `MODERATION` — to rozszerza kryterium zbioru o drugi człon.** Dotąd zbiór opisywał decyzje **ograniczające** adresata (ADR 0016). `BAN_APPEAL_ACCEPTED`/`REJECTED` mieszczą się w rozszerzonym rozumieniu pierwszego członu: **nie można odmówić odpowiedzi, o którą samemu się prosiło** — inaczej wystarczyłoby zablokować moderatora po złożeniu odwołania, żeby nigdy nie poznać wyniku własnej sprawy. `BAN_APPEAL_SUBMITTED` wchodzi z innego powodu i to jest człon nowy: to wiadomość **uruchamiająca pętlę moderacji**, jak zgłoszenie posta. Gdyby podlegała blokadom, moderator, który zablokował trolla (czyli dokładnie tę osobę, którą najpewniej sam zbanował), przestałby widzieć jej sprawy — a kanał odwoławczy umierałby po cichu, wyglądając na działający.

## Konsekwencje

- **Migracja V19 jest addytywna** — trzy nullable kolumny, zero backfillu; istniejące bany po prostu nie mają odwołania.
- **Brak historii odwołań.** Widać stan bieżącej sprawy, nie to, ile razy ktoś się odwoływał przez rok. Razem z „lista banów pokazuje stan, nie historię" (ADR 0014) to świadomy dług: historia moderacji, gdy powstanie, obejmie oba te braki naraz.
- **Odrzucenie jest ostateczne i nie ma instancji odwoławczej** — nad moderacją społeczności nie ma dziś nikogo (moderacja jest społecznościowa, nie globalna, Claude.md §8). Gdy pojawi się rola globalna, to jest naturalne miejsce na drugą instancję.
- **Moderacja nie odpowiada tekstem.** Odrzucenie niesie fakt, nie uzasadnienie — pole na odpowiedź moderatora byłoby kolejnym kanałem tekstowym w stronę ukaranego, z tym samym problemem doręczania co powód bana. Do rozważenia, gdyby odwołania okazały się częste.
- **Kolejka odwołań to lista banów**, bez osobnego widoku i bez filtra „tylko z odwołaniem". Przy dzisiejszej skali kolejka ma kilka pozycji; osobny widok to robota dla frontendu, gdy będzie potrzebny.
- **Domknięta ostatnia pozycja z konsekwencji ADR 0012.** Pętla moderacji ma teraz obie strony: decyzja → informacja (0012/0013) → zrozumienie (0015) → odpowiedź (0017).
