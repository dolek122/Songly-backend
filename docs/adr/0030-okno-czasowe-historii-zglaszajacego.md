# 30. Okno czasowe historii zgłaszającego

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

ADR 0025 uzależnił przydział zgłoszeń od historii zgłaszającego (`limit - kara × saldo odrzuceń`) i sam nazwał brak, który po sobie zostawia:

> **Reset historii nie istnieje.** Odrzucenia liczą się bez końca, więc po roku aktywności ktoś z kilkoma pomyłkami ma trwale niższy przydział. Świadomie na razie: okno czasowe na karę (np. „odrzucenia z ostatnich 90 dni") to jedna linia w zapytaniu i naturalna korekta.

Dziś bez tego okna kara jest **dożywotnia**, i to na podstawie oceny, o której ADR 0025 sam pisze, że „odrzucenie jest oceną moderatora, nie dowodem złej woli". Trzy pomyłki z zeszłego roku zostawiają człowieka na podłodze przydziału już na zawsze — a jedyną drogą wyjścia jest zdobycie trafień, czyli zgłaszanie przez kanał, który właśnie mu zwężono.

„Jedna linia w zapytaniu" przestała jednak być prawdą. Wtedy liczyliśmy odrzucenia po `post_reports`, które mają `created_at`. ADR 0027 przeszedł na liczniki `reporter_reputation`, bo uwzględnione zgłoszenia giną kaskadą razem z usuwanym postem — a **licznik nie wie, kiedy przyszło to, co zliczył**. Okno wymaga więc najpierw decyzji o kształcie danych.

## Decyzja

**Jeden wiersz na rozstrzygnięcie, z datą.** Tabela `reporter_decisions (id, user_id, community_id, outcome, decided_at)` zastępuje liczniki `reporter_reputation`; `outcome` to `UPHELD` albo `DISMISSED`. Wiersz powstaje w chwili decyzji moderatora, dokładnie tam, gdzie dotąd inkrementował się licznik.

**Okno przesuwane**, nowa właściwość `songly.content.report.history-window`, domyślnie 90 dni. Liczą się decyzje młodsze niż `now - historyWindow` — zarówno odrzucenia (kara), jak i trafienia (wybaczanie 1:1 z ADR 0027).

**Kolejka moderacji liczy w tym samym oknie**, więc `reporterUpheldCount` i `reporterDismissedCount` w `PostReportResponse` znaczą teraz „w tej społeczności, w ostatnich 90 dniach".

**Wygasanie leniwe**: żadnego procesu w tle, żadnego zadania sprzątającego. Kara przestaje działać, bo zapytanie przestaje ją widzieć.

Migracja przenosi liczniki na wiersze, datując je na `updated_at` wiersza licznika.

## Uzasadnienie

**Dlaczego wiersz na decyzję, a nie data przy liczniku.** Dołożenie do licznika jednego `window_started_at` i zerowanie go po wygaśnięciu dawałoby okno **skokowe**, a nie przesuwane: cały wiersz zerowałby się naraz, więc odrzucenie z wczoraj przepadałoby razem z rocznymi. To nie jest tylko nieprecyzyjne — to jest **wykorzystywalne**. Cierpliwy troll trafia ze swoją serią w moment przed przesunięciem i wychodzi z czystą kartą po jednym dniu zamiast po dziewięćdziesięciu. Przy wierszach z datami każda decyzja żyje dokładnie tyle, ile ma żyć, i nie ma granicy, w którą można celować.

**Dlaczego nie wracamy do liczenia po `post_reports`.** To by odtworzyło błąd, który naprawił ADR 0027: uwzględnienie zgłoszenia = usunięcie posta = kaskadowe usunięcie jego zgłoszeń, więc historia **trafnych** zgłoszeń ginęła dokładnie wtedy, gdy okazywały się trafne. `reporter_decisions` nie ma klucza do posta i to jest cały powód istnienia tej tabeli, odziedziczony po poprzedniku.

**Dlaczego 90 dni.** Na tyle długo, żeby zasypywanie kolejki nie opłacało się przez przeczekanie (kwartał to nie „wróć po weekendzie"), i na tyle krótko, żeby pomyłka nie była dożywotnia. Liczba jest właściwością konfiguracyjną, bo to jest dokładnie ten parametr, który zweryfikuje ruch — nie ma dziś danych, na podstawie których dałoby się go obronić dokładniej niż jako rząd wielkości.

**Dlaczego okno obejmuje też trafienia.** Gdyby starzały się tylko odrzucenia, saldo z ADR 0027 zaczęłoby porównywać wielkości z różnych epok: trafienia z całego życia konta przeciw odrzuceniom z kwartału. Ktoś, kto rok temu zgłaszał dobrze, miałby dziś praktycznie nieusuwalny kredyt na nietrafione zgłoszenia. Historia ma być obrazem tego, jak ten człowiek zgłasza **teraz**, a nie sumą dwóch różnych okresów.

**Dlaczego kolejka też widzi tylko okno.** Te same liczby sterują karą, więc rozjazd między „co liczy system" a „co widzi moderator" byłby mylący w najgorszym momencie — przy ocenie sprawy. Poza tym liczba bez skali czasu nie jest wagą, którą człowiek umie rozważyć: „3 odrzucenia" znaczy coś zupełnie innego w tym tygodniu niż dwa lata temu, a w kolejce widać tylko liczbę. Dwie pary liczb (życie + okno) byłyby czterema liczbami na pozycję w kolejce — szumem, za który nikt nie zapłacił.

**Dlaczego migracja datuje wszystko na `updated_at`.** Dokładnych dat nie ma i nie da się ich odzyskać; `updated_at` to moment ostatniej zmiany licznika, czyli najpóźniejszy moment, w którym którakolwiek z tych decyzji mogła zapaść. Wybór jest świadomie ostrożny: przesuwa stare decyzje **do przodu**, więc kara wygasa najpóźniej, jak może. Odwrotna pomyłka wybaczałaby po migracji odrzucenia, które powinny liczyć się jeszcze tygodniami — czyli ten sam błąd, przed którym migracja z ADR 0027 chroniła, przenosząc odrzucenia zamiast zerować je od nowa.

## Konsekwencje

**Zmiana zachowania bez zmiany kontraktu.** Żaden endpoint nie zyskuje ani nie traci pola; zmienia się znaczenie dwóch liczb w kolejce moderacji (teraz „w oknie") i to, że przydział sam wraca do bazowego. `openapi.json` bez zmian.

**Jedna pułapka, na którą warto uważać przy następnej projekcji Spring Data.** Pierwsza wersja repozytorium zwracała `outcome` jako kolumnę projekcji i wywalała każde zgłoszenie 500-tką: `IllegalAccessError: failed to access class ReporterDecisionOutcome from class jdk.proxy2.$Proxy298`. Spring Data realizuje projekcje interfejsowe proxy JDK, a ono nie ma dostępu do typu **package-private** — a package-private jest tu regułą, nie przypadkiem (Claude.md §3). Błąd wychodzi wyłącznie w czasie wykonania, kompilator go nie widzi. Rozstrzygnięcie jest więc parametrem zapytania, a nie kolumną: dwa zapytania po tym samym indeksie są tańsze niż upublicznianie typu, którego nie potrzebuje żaden inny moduł.

**Tabela rośnie liniowo z liczbą decyzji moderacyjnych**, zamiast mieć jeden wiersz na parę (osoba, społeczność). Wolumen to działania moderatorów, nie użytkowników — rzędy wielkości mniej niż `post_reports`, więc nie dokładamy zadania sprzątającego. Gdy kiedyś urośnie, kasowanie wierszy starszych niż okno jest bezpieczne **z definicji**: po przekroczeniu `historyCutoff` nikt ich już nie czyta. To jest dokładnie ta własność, której nie miały `sessions` (tam wiersz trzeba było zachować na diagnozę, patrz §9).

**Dwa okna w jednej decyzji o przyjęciu zgłoszenia** — `window` (natężenie, godzina) i `historyWindow` (pamięć, kwartał). Pomieszanie ich daje albo limit bez pamięci, albo karę na godzinę, więc oba mają javadoc mówiący wprost, że nie są tym samym. Test integracyjny ma z tego powodu dwa różne pomocniki (`slideWindow` na `post_reports`, `ageDecisions` na `reporter_decisions`) i przypadek, który używa obu — bez przesunięcia okna limitu odmowa brałaby się ze zgłoszeń siedzących w tej godzinie, a nie z kary, i test nie dowodziłby niczego.

**Czego to nie robi.** Kara nie maleje stopniowo — decyzja waży tyle samo w dniu 1 i w dniu 89, a w dniu 91 nie waży nic. Wygaszanie ciągłe (waga malejąca z wiekiem) byłoby ładniejsze i nikt by nie umiał powiedzieć, czy działa lepiej; przy jednym progu da się przynajmniej wyjaśnić człowiekowi, dlaczego dostał odmowę. Historia nadal nie ma też związku z **wagą** sprawy: odrzucone zgłoszenie oczywistego spamu liczy się tyle samo, co odrzucone zgłoszenie w sprawie ocennej.
