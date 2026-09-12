# 29. Ważone dopasowywanie po gatunkach

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

Dopasowywanie działa od fazy 2 na surowym współczynniku Jaccarda: część wspólna gatunków przez sumę unikalnych (Claude.md §8). Było to świadomie najprostsze możliwe rozwiązanie — „tanie obliczeniowo, wystarczające żeby zweryfikować, czy funkcja w ogóle ma sens".

Ma jednak wadę, która nie jest kwestią precyzji, tylko sensu wyniku: **każdy gatunek waży tyle samo**. „Pop", który wskazała połowa serwisu, liczy się dokładnie tak jak „Dark Ambient" wskazany przez dwie osoby. Skutek jest dokładnie odwrotny do celu funkcji — ludzie z mainstreamowym gustem wyglądają na identycznie dopasowanych do wszystkich, bo wspólny „Pop" nie mówi o nikim niczego. Jedyne wspólne gatunki, które naprawdę o czymś świadczą, są rozcieńczane tymi, które nie świadczą o niczym.

Druga rzecz wyszła przy okazji: kandydaci nie byli filtrowani po zawieszeniu konta. ADR 0026 ukrył treści zawieszonych i odciął ich z wyszukiwarki profili, ale dopasowywanie zostało poza tą zmianą — więc konto, któremu platforma odebrała dostęp (zwykle za nękanie), dalej trafiało do propozycji kontaktu.

## Decyzja

**Ważony Jaccard.** Wagą gatunku jest jego rzadkość: `ln(1 + N / n)`, gdzie `N` to liczba użytkowników mających jakiekolwiek ulubione gatunki, a `n` — liczba tych, którzy wskazali ten gatunek. Wynik to suma wag części wspólnej przez sumę wag złączenia.

**Popularność liczona globalnie**, nie wśród kandydatów z tego jednego żądania — przez dwie nowe metody `ProfileDirectory`: `genreUserCounts(genreIds)` i `usersWithAnyGenreCount()`. Obie idą jednym zapytaniem agregującym, bez wczytywania wierszy `user_genres`.

**Zawieszone konta wypadają z kandydatów** — przez `SuspendedAccounts` z ADR 0022, tak samo jak robi to już `content` i `profile`.

Bez zmian: brak tabeli `match_scores` (liczymy na żądanie), brak ML (Claude.md §8), niezmieniony kontrakt API — `score` dalej mieści się w (0, 1].

## Uzasadnienie

**Dlaczego akurat `ln(1 + N/n)`.** To jest kształt wagi IDF i wybieramy go za dwie własności brzegowe. Waga **nigdy nie spada do zera**, więc wspólny gatunek mainstreamowy nadal coś znaczy (przy `n = N` wychodzi `ln 2`) — inaczej dwie osoby lubiące wyłącznie „Pop" miałyby wynik 0, co jest fałszem. I **nigdy nie ucieka w nieskończoność**: logarytm rośnie wolno, więc jeden wspólny unikat nie przesłania całego profilu. Jedynka pod logarytmem zabezpiecza przed `ln 0`; gatunek bez ani jednego wskazania nie może pojawić się w żadnym zbiorze, więc dzielenia przez zero tu nie ma.

**Dlaczego globalnie, a nie wśród kandydatów.** Popularność liczona w próbce zmieniałaby się w zależności od tego, kto pyta — te same dwie osoby widziałyby **różne wyniki dopasowania do siebie samych**, zależnie od tego, która otworzyła ekran. Poza tym „niszowy" ma znaczyć „rzadki w serwisie"; rzadkość w przypadkowej próbce to nie ta sama informacja.

**Mianownik zostaje złączeniem**, nie liczbą wspólnych gatunków. Człowiek, który wskazał wszystko, ma być słabym dopasowaniem do każdego — jego zbiór nie niesie informacji o guście, a dzielenie samą częścią wspólną dawałoby mu wynik 1 wobec całego serwisu.

**Dlaczego to nie jest krok w stronę ML.** Wciąż jest to jedna formuła nad danymi, które user sam o sobie podał — nic się nie uczy, nie ma modelu do trenowania ani danych o zachowaniu (te zbieramy dopiero, Claude.md §8). Zmienia się waga składnika, nie natura metody, i wynik da się wyjaśnić jednym zdaniem osobie, której dotyczy.

**Dlaczego zawieszeni wypadają.** Dopasowanie nie jest odczytem treści, tylko **zaproszeniem do kontaktu** — proponowanie kontaktu z kimś, komu platforma właśnie odebrała dostęp za nękanie, jest wprost szkodliwe dla osoby, której to proponujemy. To ten sam argument, którym ADR 0026 uzasadnił ukrycie treści; tam zabrakło go tylko w tym jednym miejscu.

## Konsekwencje

**Więcej wspólnych gatunków nie znaczy już automatycznie wyżej.** Jeden wspólny gatunek niszowy może przebić dwa wspólne mainstreamowe — i to jest cel zmiany, nie jej skutek uboczny. Trzeba mieć to z tyłu głowy przy czytaniu rankingu i przy pisaniu testów: `MatchingFlowIntegrationTest` przestał asertować kolejność wynikającą z liczby trafień, bo taka asercja utrwalałaby dokładnie to zachowanie, które usuwamy. Kolejność pilnuje test, który sam ustawia popularność gatunków.

**Wyniki zależą teraz od stanu całej bazy**, nie tylko od dwóch porównywanych profilów. Dwa skutki: ten sam wynik liczbowy z dwóch różnych dni nie musi być tą samą wartością (gdy gatunek w międzyczasie spopularniał), a testy nie mogą przybijać dokładnych liczb — dokładna arytmetyka sprawdza się tylko przy jawnie ustawionej popularności (`MatchingServiceTest.allGenresEquallyPopular`: przy równej popularności wagi skracają się i ważony Jaccard jest identyczny z surowym).

**Dwa dodatkowe zapytania na żądanie** — agregacja popularności i globalny licznik. Oba na `user_genres`, obie bez wczytywania encji. Przy obecnej skali to nic; gdy zacznie kosztować, naturalny krok to cache popularności w Redisie z TTL rzędu godzin, bo popularność gatunku zmienia się wolno. Nie robimy tego teraz, bo byłoby to optymalizowanie pod ruch, którego nie ma.

**Czego to nadal nie robi.** Nie bierze pod uwagę ani liczby gatunków jako sygnału pewności (profil z jednym gatunkiem daje mniej wiarygodne dopasowanie niż z dziesięcioma, a traktowany jest równorzędnie), ani zachowania (co user faktycznie słucha, co lubi), ani wzajemnego podobieństwa gatunków — „Death Metal" i „Black Metal" są dla nas równie odległe jak „Death Metal" i „Jazz". Pierwsze wymaga decyzji produktowej o progu, ostatnie — taksonomii gatunków, której nie mamy; oba czekają na dane mówiące, czy w ogóle przeszkadzają.

**Blokady załatwia warstwa niżej**, bez zmian: kandydaci przechodzą już przez `SocialGraph.blockedEitherWayIds`.
