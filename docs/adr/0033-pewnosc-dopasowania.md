# 33. Pewność dopasowania: wynik przygaszany ilością danych

Data: 2026-09-13
Status: zaakceptowane

## Kontekst

ADR 0029 zapisał w konsekwencjach brak, który zostawił nietknięty:

> Nie bierze pod uwagę liczby gatunków jako sygnału pewności (profil z jednym gatunkiem daje mniej wiarygodne dopasowanie niż z dziesięcioma, a traktowany jest równorzędnie).

Skutek jest łatwy do pokazania. Dwie osoby, które wskazały po jednym gatunku i jest to ten sam gatunek, dostawały **1.0** — wynik doskonały, wyliczony z jednego punktu danych po każdej stronie. Ktoś, kto dzieli z nami siedem gatunków z dziesięciu, dostawał 0.7 i stał **niżej**. Ranking premiował więc profile puste, a najwyższe wyniki w serwisie z definicji należały do ludzi, o których nie wiadomo nic.

To nie jest wada wagi rzadkości z ADR 0029 — to wada każdego współczynnika typu Jaccard. On mierzy **proporcję pokrycia**, a proporcja z małej próbki jest skrajna: przy jednym gatunku wychodzi 1 albo 0, i nigdy nic pomiędzy. Skrajność bierze się z braku danych, a wygląda jak pewność.

## Decyzja

**Stała w mianowniku.** Wynik to waga części wspólnej podzielona przez wagę złączenia **powiększoną o stałą** `confidence-prior` (domyślnie 2.0, w jednostkach wag gatunków — z grubsza jeden typowy gatunek).

**Pewność liczy złączenie**, nie część wspólną: `confidence = W(złączenia) / (W(złączenia) + prior)`.

**`confidence` wychodzi w odpowiedzi** obok `score`, jako pole dodatkowe w `MatchResponse`. Sortujemy nadal po `score`.

Bez progu odcięcia: nikt nie znika z propozycji z powodu ubogiego profilu.

## Uzasadnienie

**Dlaczego stała w mianowniku, a nie osobny mnożnik.** Chcieliśmy `wynik × pewność`. Po podstawieniu obu definicji:

```
W(wspólne)/W(złączenie) × W(złączenie)/(W(złączenie) + prior) = W(wspólne)/(W(złączenie) + prior)
```

Cała zmiana to jedno dodawanie. To nie jest sztuczka rachunkowa, tylko sygnał, że wybrana definicja pewności pasuje do wybranej definicji podobieństwa: wychodzi klasyczne wygładzanie, bez drugiego parametru, bez gałęzi w kodzie i bez ryzyka, że dwa mechanizmy zaczną się rozjeżdżać. Interpretacja jest przy tym prosta do wytłumaczenia człowiekowi: **udajemy, że po obu stronach jest zawsze trochę gustu, którego nie znamy**. Przy ubogim profilu ten fantom dominuje, przy bogatym znika w tle.

**Dlaczego pewność to złączenie, a nie część wspólna.** To jest sedno tej decyzji i łatwo ją tu pomylić. Pewność ma mierzyć **ile danych mamy**, a nie **jak dobry jest wynik**. Przypadek rozstrzygający: „mam dziesięć gatunków, ty dziesięć, wspólny jeden". Część wspólna jest malutka, ale to jest sytuacja, w której wiemy o obu stronach dużo i odpowiedź „nie pasujecie" jest **pewna**. Gdyby pewność liczyła część wspólną, oznaczalibyśmy ten przypadek jako niepewny i podbijali wynik komuś, kto ewidentnie nie pasuje. Nieufność należy się małym zbiorom, nie słabym wynikom.

**Dlaczego wygaszanie do zera, a nie do średniej.** Wygaszanie do średniej populacji byłoby statystycznie eleganckie, ale wymagałoby liczenia i utrzymywania tej średniej — a przede wszystkim znaczyłoby „gdy nie wiem, zakładam, że jesteście przeciętnie dopasowani". Wolimy „gdy nie wiem, nie twierdzę, że pasujecie": brak danych nie może wyglądać jak dowód.

**Dlaczego bez progu odcięcia.** Naturalny odruch to „nie pokazuj dopasowań poniżej N gatunków". To jednak karze nowych użytkowników pustą listą dokładnie wtedy, gdy dopiero poznają funkcję — a pusta lista jest gorsza niż lista uczciwie oznaczona jako niepewna. Wygaszanie porządkuje ranking, nikogo z niego nie usuwając; próg usuwałby ludzi, nie poprawiając kolejności.

**Dlaczego `confidence` wychodzi na zewnątrz.** Przygaszony `score` jest dobry do sortowania, ale klient nie ma z niego jak odczytać, **dlaczego** jest niski: „35%" wygląda tak samo przy słabym dopasowaniu i przy dopasowaniu nieznanym. Z drugą liczbą interfejs może powiedzieć „92% zgodności, ale oparte na jednym gatunku" albo w ogóle nie pokazywać procentu, dopóki nie ma czym go poprzeć. Decyzję o **rankingu** zostawiamy po stronie serwera (żeby żaden klient jej nie pominął), a decyzję o **prezentacji** oddajemy klientowi, bo to on wie, ile miejsca ma na ekranie.

**Dlaczego stała jest konfigurowalna.** Jest to jedyny parametr tej zmiany i jedyny, co do którego nie mamy danych — dobraliśmy go rozumowaniem („około jednego typowego gatunku"), a nie pomiarem. Ustawienie `0` przywraca dokładnie zachowanie sprzed tego ADR-u, co jest zarówno wentylem bezpieczeństwa, jak i dobrą definicją tego, co ta zmiana robi.

## Konsekwencje

**Wszystkie wyniki spadły, najbardziej przy ubogich profilach.** To zmiana widocznych liczb bez zmiany kształtu kontraktu — dokładnie jak w ADR 0029, który też przeliczył wszystkim wyniki. `score` nadal jest jedyną liczbą do sortowania i nadal rośnie wraz z dopasowaniem.

**1.0 jest teraz nieosiągalne**, więc przedział zmienia się z (0, 1] na (0, 1). Jest to zamierzone i warte powiedzenia wprost: „100% dopasowania" byłoby twierdzeniem, którego te dane nigdy nie uniosą — wiemy tylko tyle, ile ktoś sam o sobie zaznaczył. Klient, który zakładał, że 1.0 jest osiągalne (np. rysował pasek „do pełna"), zobaczy pasek, który nigdy się nie wypełnia.

**Testy jednostkowe straciły dokładne liczby.** Do tej pory przy jednakowej popularności wagi skracały się w ułamku i wychodził surowy Jaccard, więc dało się asertować `0.5` czy `1.0`. Stała w mianowniku nie skraca się z niczym, a waga jest logarytmem, więc **nie istnieje** popularność dająca okrągły wynik. Testy sprawdzają teraz kolejność, przedziały i różnice — i tak jest uczciwiej, bo dawne stałe i tak były artefaktem szczególnego doboru danych, a nie własnością algorytmu.

**Pojawia się drugie pytanie do interfejsu**: co pokazać przy niskiej pewności. Backend go nie rozstrzyga i nie powinien; zostawia klientowi liczbę i konkret (`sharedGenreIds`), z których da się zbudować zdanie w rodzaju „oboje lubicie Dark Ambient".

**Czego to nadal nie robi.** Nie rozróżnia „profil ubogi, bo nowy" od „profil ubogi, bo ktoś świadomie wskazał jeden gatunek" — jedno i drugie to dla nas tyle samo danych, i słusznie. Nie zachęca też do uzupełnienia profilu; naturalny następny krok to podpowiedź „dodaj gatunki, żeby lepiej dopasowywać", ale to jest robota po stronie klienta. Nadal aktualne zostają też dwa braki z ADR 0029: brak podobieństwa **między** gatunkami i brak danych o zachowaniu.
