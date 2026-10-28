# 26. Ukrywanie treści zawieszonego konta

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

ADR 0022 odciął zawieszone konto od pisania **i** czytania, ale sam zapisał w konsekwencjach, czego nie zrobił: „zawieszone konto zostaje widoczne dla innych — profil dalej jest w wyszukiwarce, a posty w feedach".

W praktyce znaczyło to, że sankcja nie dotykała tego, co było jej powodem. Zawieszało się kogoś za nękanie, a nękające posty i komentarze stały dalej, profil był w wyszukiwarce (czyli dokładnie tam, gdzie się kogoś szuka), a pod ukrytym-dla-autora postem dalej dało się komentować. Zawieszenie wyglądało więc jak kara wyłącznie dla zawieszonego, przy niezmienionej szkodzie dla wszystkich pozostałych.

## Decyzja

Treści kont z **aktywnym** zawieszeniem są ukrywane we wszystkich odczytach modułów `content` i `profile`:

- `content`: zbiór zawieszonych dochodzi do `hiddenAuthorsFor(viewerId)` — jednego punktu, przez który przechodzą już wszystkie ścieżki tego modułu;
- `profile`: wyszukiwarka odsiewa ich w zapytaniu, a `GET /profiles/{id}` zwraca dla nich 404.

Nowy interfejs publiczny `SuspendedAccounts` w module identity zwraca identyfikatory zawieszonych kont.

## Uzasadnienie

- **Ukrywamy przy odczycie, nigdy nie kasujemy — to jest warunek, nie wygoda.** Zawieszenia bywają czasowe (ADR 0014) i bywają zdejmowane, także po uznaniu odwołania (ADR 0023); kasowanie treści byłoby nieodwracalne, więc zamieniałoby zawieszenie na karę **cięższą od samego zawieszenia**. Dokładnie ten sam argument, którym ADR o blokadach uzasadnił filtrowanie skrzynki powiadomień zamiast czyszczenia jej.
- **Jeden punkt dopisania w content, nie siedem.** `hiddenAuthorsFor` istniał od blokad i przechodzą przez niego feed, lista publiczna, oś czasu, posty społeczności, pojedynczy post, lista i licznik komentarzy oraz ścieżki zapisu (lajk, udostępnienie, komentarz). Dodanie tam zawieszonych pokrywa wszystko naraz i — co ważniejsze — **nowa ścieżka odczytu dostanie ten filtr automatycznie**, bo i tak musi wziąć ten zbiór. Gdyby tego punktu nie było, ta zmiana byłaby siedmioma osobnymi poprawkami, z których pierwsza zapomniana tworzyłaby cichą dziurę.
- **Profil zawieszonego to 404, nie 403.** Status konta nie jest informacją dla osób postronnych — ta sama zasada, którą moduł content stosuje do postów zablokowanych autorów i moduł social do cudzych zaproszeń. Ukrycie w wyszukiwarce bez ukrycia samego profilu byłoby przy tym środkiem połowicznym: wystarczyłby zapamiętany link.
- **Wyszukiwarka filtruje zawieszenia, choć nie filtruje blokad — i to nie jest niekonsekwencja.** Blokada jest relacją dwóch osób, czyli danymi modułu `social`, od którego `profile` zależeć nie może (cykl, §2 — powód zapisany przy blokadach). Zawieszenie jest **stanem konta**, czyli danymi modułu `identity`, od którego `profile` zależy już dziś. Granica modułów rozstrzyga to za nas.
- **Filtr w zapytaniu, nie po pobraniu strony** — odsiewanie po fakcie psuje paginację (strona o rozmiarze 20 wracałaby z 19 wynikami, a `totalElements` liczyłby też ukrytych). Uwaga na pułapkę SQL-a: `not in ()` nie jest prawdą dla żadnego wiersza, więc pusty zbiór zawieszeń wygasiłby wyszukiwarkę wszystkim. Jest tu bezpieczny tylko dlatego, że do tego samego zbioru wrzucamy pytającego, więc nigdy nie jest pusty — dokładnie ta sama pułapka, o którą potknęły się blokady.
- **`SuspendedAccounts` zwraca cały zbiór, nie odpowiedź per użytkownik.** Konsumenci filtrują całe strony wyników, więc pytanie „czy ten jest zawieszony" zadane dla każdego wiersza dałoby N+1. Zbiór jest z natury mały: zawieszenia nakłada ręcznie administrator.
- **Wygasłe zawieszenia nie wchodzą do zbioru**, więc treści wracają same, bez niczyjej akcji — tak samo jak samo konto (ADR 0014). Żadnego zadania sprzątającego, żadnego „odkrywania" do wykonania.

## Konsekwencje

- **Jedno dodatkowe zapytanie na żądanie odczytu** w content i profile. Tak samo jak przy blokadach, a zbiór jest mniejszy; gdyby kiedyś zaczęło to ważyć, jest to idealny kandydat na cache w Redisie (mały, globalny, zmieniany ręcznie) — ale dopisywanie go teraz byłoby optymalizacją bez pomiaru.
- **Komentarze zawieszonego znikają razem z licznikiem**, bo `hiddenAuthorsFor` filtruje oba naraz — inaczej nad listą trzech komentarzy pisałoby „5 komentarzy" (ten sam powód co przy blokadach).
- **Lajki i udostępnienia zawieszonego zostają w licznikach.** To czyste liczby bez tożsamości, więc nie da się z nich odczytać, że ten człowiek tu był — identyczna granica jak przy blokadach.
- **Powiadomienia sprzed zawieszenia zostają w skrzynkach innych osób.** Filtr blokad w `NotificationService` nie obejmuje zawieszeń; dopisanie ich byłoby spójne, ale te zaczepki już dotarły, a skrzynka jest prywatnym zapisem tego, co się stało. Do rozważenia, gdyby zawieszenia zaczęły być częste.
- **Status „co teraz gram" nie jest filtrowany osobno** — zawieszone konto nie może go odświeżyć (traci dostęp do API), a statusy wygasają same, więc problem rozwiązuje się w kilka minut bez dodatkowego kodu. **Nieaktualne od ADR 0049:** status odświeża serwerowy poll na zapisanych tokenach Spotify, a ręczny żyje godzinami, więc zawieszone konto nadawało dalej. Status zawieszonego jest teraz filtrowany razem z przełącznikiem widoczności.
- **Zawieszony nie widzi, że jego treści są ukryte**, bo nie widzi niczego (ADR 0022). Po zdjęciu zawieszenia wszystko wraca, więc nie ma o czym informować.
