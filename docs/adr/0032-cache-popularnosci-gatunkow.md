# 32. Cache popularności gatunków w Redisie

Data: 2026-09-13
Status: zaakceptowane

## Kontekst

ADR 0029 zważył dopasowania rzadkością gatunku i zapisał koszt tej zmiany wprost:

> **Dwa dodatkowe zapytania na żądanie** — agregacja popularności i globalny licznik. Przy obecnej skali to nic; gdy zacznie kosztować, naturalny krok to cache popularności w Redisie z TTL rzędu godzin, bo popularność gatunku zmienia się wolno.

Robimy ten krok **przed** pojawieniem się ruchu, który by go wymuszał, i trzeba to nazwać: nie jest to reakcja na zmierzony problem. Uzasadnienie jest inne — te dwie liczby są nietypowo dobrym kandydatem na cache i nietypowo tanim w utrzymaniu, bo mają trzy cechy naraz, których zwykle brakuje: są **globalne** (jeden wpis obsługuje każde żądanie każdego użytkownika), zmieniają się **rzadko** (tylko przy edycji ulubionych gatunków) i ich lekka nieaktualność jest **niegroźna** (jedna osoba więcej przy „Popie" nie zmienia niczyjego wyniku). Cache, który pojawi się później, będzie dokładnie taki sam — więc jedyne, co zyskujemy na czekaniu, to świadomość, że był potrzebny.

## Decyzja

**Jeden wpis w Redisie na całą instalację** (`genre-popularity`): pełna tabela popularności wszystkich gatunków plus mianownik (liczba użytkowników z jakimikolwiek gatunkami), jako jeden JSON.

**Cache pod granicą modułu.** `ProfileDirectory` nie zmienia się ani o jedną metodę — popularność jest danymi modułu `profile`, więc to on je cache'uje. `matching` nie wie o Redisie.

**Unieważnianie przy każdej zmianie**, TTL 1h tylko jako siatka bezpieczeństwa. Obie ścieżki ruszające `user_genres` (ustawienie ulubionych gatunków, usunięcie konta) publikują wewnętrzne zdarzenie modułu, a listener unieważnia wpis **po commicie**.

**Fail-open w każdej operacji**: awaria Redisa oznacza policzenie liczb z bazy, nie błąd dopasowań.

## Uzasadnienie

**Dlaczego cała tabela, a nie podzbiór z żądania.** Gatunki to zamknięty słownik z migracji (dziś piętnaście pozycji), więc całość jest mniejsza niż narzut na cokolwiek mądrzejszego. Ale ważniejszy jest drugi powód: klucze per podzbiór rozpadłyby się na wpisy o różnym wieku, a wtedy wagi dwóch gatunków w jednym wyniku pochodziłyby z różnych epok — czyli dokładnie ta niespójność, przed którą ADR 0029 broni się, licząc popularność globalnie. Jeden wpis znaczy jeden moment w czasie dla wszystkich liczb.

**Dlaczego unieważnianie, a nie samo TTL.** Z samym TTL kara byłaby symetryczna do ADR 0030: prosta, ale skokowa i nieprzewidywalna. Przy unieważnianiu obraz jest aktualny **zawsze** poza sytuacjami, w których ktoś obszedł aplikację, a TTL przestaje być mechanizmem świeżości i staje się zabezpieczeniem na migrację czy ręczny `UPDATE` — dlatego może być długi. Koszt unieważniania jest przy tym bliski zeru, bo edycja ulubionych gatunków jest operacją rzadką w porównaniu z otwieraniem ekranu dopasowań; w patologicznym przypadku (wszyscy nieustannie edytują gatunki) cache degraduje się do stanu sprzed tego ADR-u, a nie do czegoś gorszego.

**Dlaczego unieważnianie po commicie, i dlaczego to jest warunek poprawności.** Unieważnienie w trakcie transakcji otwiera okno, w którym równoległe żądanie przelicza obraz, **nie widząc** jeszcze niezacommitowanych zmian, i zapisuje go ze świeżym TTL. Zbyt wczesne unieważnienie nie tylko więc nie pomaga — utrwala nieaktualne liczby na pełną godzinę. To ta sama rodzina pułapek co kasowanie plików w Claude.md §9: praca poza transakcją musi czekać na commit, bo dopiero commit mówi, jak jest. Różni je koszt pomyłki: przedwczesne kasowanie pliku niszczy dane, przedwczesne unieważnienie cache'a po cichu przywraca to, co miało zniknąć.

**Dlaczego `ProfileDirectory` zostaje bez zmian, choć ładniej byłoby jedną metodą.** Popularność i mianownik tworzą jeden ułamek `N/n`, więc kuszące jest zwrócenie ich jednym typem. Reguła 1 `ModuleBoundariesTest` przepuszcza jednak przez granicę modułu wyłącznie **publiczne interfejsy i zdarzenia** (Claude.md §3), a rekord z parą liczb jest ani jednym, ani drugim. Interfejs w roli pary liczb byłby ceremonią droższą niż problem, który rozwiązuje — zwłaszcza że oba odczyty trafiają w ten sam, niepodzielny wpis. Rozjazd wymagałby wygaśnięcia klucza dokładnie **między** dwoma odczytami jednego żądania, a jego skutkiem byłaby waga policzona z sąsiedniej epoki, nie błąd.

**Dlaczego fail-open.** Cache jest optymalizacją, nie warunkiem poprawności — ta sama zasada, którą `RateLimitingFilter` stosuje do limitów (§9): awaria Redisa nie może odciąć funkcji, która potrafi działać bez niego. Uszkodzony wpis traktujemy jak brak wpisu: policzymy z bazy i nadpiszemy, zamiast wywracać żądanie na cudzym, niezrozumiałym JSON-ie.

**Dlaczego w `profile`, a nie w `matching`.** Cache'owanie po stronie konsumenta znaczyłoby, że każdy następny konsument popularności pisze je sobie od nowa — a popularność gatunków jest danymi `profile` i to on wie, kiedy przestają być aktualne. Tu widać to szczególnie dobrze: unieważnianie wisi na tych samych dwóch metodach, które zmieniają `user_genres`, i obie są w `profile`.

## Konsekwencje

**Liczby z `ProfileDirectory` mogą być spóźnione** — do godziny, gdy zmiana obeszła aplikację, i praktycznie nigdy w normalnej pracy. Javadoc interfejsu mówi to wprost, żeby następny konsument nie użył ich tam, gdzie potrzebna jest liczba dokładna na teraz. Dopasowywanie takiej dokładności nie potrzebuje; gdyby kiedyś pojawił się konsument, który potrzebuje, powinien dostać własną metodę liczącą z bazy, a nie zmieniać zachowanie tej.

**Test musi zmieniać liczby obok aplikacji**, bo każda ścieżka aplikacji unieważnia cache — więc przez API nie da się doprowadzić do rozjazdu, który jest tu do sprawdzenia. `GenrePopularityCacheIntegrationTest` dopisuje wiersz `user_genres` SQL-em i sprawdza obie strony: że obraz jest podawany z cache'a i że zmiana przez API go unieważnia.

**Fail-open sprawdza test jednostkowy, nie integracyjny**, i tak musi zostać: Redis w Testcontainers działa, a zatrzymanie go w trakcie testu rozwaliłoby resztę suity, bo kontenery są wspólne dla całego przebiegu. `GenrePopularityCacheTest` podstawia zamiast niego mock rzucający wyjątkiem — trzy operacje, trzy przypadki, plus wpis-śmieć.

**Zapytanie zmieniło kształt** z filtrowanego po gatunkach na pełną agregację (`countUsersByGenre`). Przy piętnastu gatunkach to bez znaczenia, ale gdyby słownik gatunków kiedyś urósł do tysięcy — albo, co ważniejsze, gdyby gatunki zaczęli tworzyć użytkownicy — pełna tabela w jednym wpisie przestanie być właściwym kształtem i trzeba będzie wrócić do tej decyzji.

**Nie ma metryki trafień cache'a.** Micrometer mierzy u nas to, czego inaczej nie widać (§13), a tu skutek chybienia jest widoczny w `http.server.requests` na endpointcie dopasowań i w liczbie zapytań do bazy. Jeśli kiedyś okaże się, że obraz wygasa częściej, niż wynikałoby z ruchu, właściwą serią jest `hit/miss` — ale dodawanie jej teraz byłoby mierzeniem cache'a, którego nikt jeszcze nie potrzebował.
