# 14. Bany czasowe

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0011 dał bany wyłącznie bezterminowe. Między „nic" a „dożywotnio" nie było niczego — a najczęstsza realna decyzja moderacyjna to właśnie środek tej skali: „tydzień przerwy". Moderator z samym banem bezterminowym albo eskaluje na wyrost, albo odpuszcza; obie drogi psują społeczność. ADR 0012 odnotował też, że „wygaśnięcie bana jako powiadomienie nie ma czego reprezentować" — bo nie istniało samo wygaśnięcie.

## Decyzja

`community_bans.expires_at` (nullable, `NULL` = bezterminowy). Żądanie bana przyjmuje opcjonalne `durationDays` (min. 1, bez górnej granicy); odpowiedź listy banów niesie `expiresAt`. **Wygasanie jest leniwe**: wszystkie zapytania o ban pytają o ban aktywny (`expires_at is null or expires_at > now`), żaden proces nie budzi się w chwili wygaśnięcia. Wygasły wiersz zostaje w tabeli; ponowny ban tej samej osoby nadpisuje go w całości (`reimpose`) i publikuje nowe zdarzenie.

## Uzasadnienie

- **Kolumna na istniejącym wierszu, nie osobna tabela ani historia banów.** Klucz główny `(community_id, user_id)` już mówi: jedna para — jeden ban. Stan „zbanowany do kiedyś" to dokładnie ten sam byt co „zbanowany", różni się jednym polem. Historia banów (kto, kiedy, ile razy) to inna potrzeba — dziś nikt jej nie czyta, a dopisywanie jej „przy okazji" zostawiłoby tabelę, której nic nie używa.
- **Wygasanie leniwe, bez zadania sprzątającego.** Sesje dostały `SessionCleanupJob`, bo rosły z każdym logowaniem bez górnej granicy; banów jest co najwyżej jeden na parę (społeczność, użytkownik) i wiersz po wygaśnięciu jest natychmiast ponownie używany przez `reimpose`. Sprzątanie rozwiązywałoby problem, którego struktura danych nie ma. Filtr w zapytaniu jest przy tym jedynym mechanizmem poprawności — job byłby wyłącznie higieną.
- **`isBanned` pozostaje jedynym punktem egzekwowania.** Moduł content pyta przez `CommunityMembership.isBanned` (§2) i nie zmienia ani linii — wygasanie w całości siedzi w zapytaniach modułu community (`existsActive`). Warunek jest alternatywą, więc metody wyprowadzone Spring Data ustąpiły `@Query` — ta sama historia co przy filtrze blokad (ADR 0012).
- **Ponowny ban po wygaśnięciu to nowa decyzja i nowe powiadomienie.** Reguła „tylko nowy ban powiadamia" (ADR 0012) znaczy od teraz: tylko ban zmieniający stan z „niezbanowany" na „zbanowany". Bez tego doprecyzowania wygasły wiersz wyciszałby każdy kolejny ban tej samej osoby na zawsze — recydywista nigdy więcej nie dostałby informacji, że jest zbanowany.
- **Ban aktywny jest nietykalny dla powtórnego bana.** Zmiana terminu w locie rozmywałaby idempotencję: powtórzone kliknięcie moderatora (retry, drugi moderator w tej samej sprawie) po cichu podmieniałoby termin. Przedłużenie albo skrócenie to unban + ban — dwie świadome decyzje, każda z własnym skutkiem. Świadomy koszt UX; do rewizji, gdyby moderatorzy realnie się o to potykali.
- **Termin musi dotrzeć do zbanowanego.** „Tydzień przerwy" działa tylko na kimś, kto wie, że to tydzień — ban czasowy nieodróżnialny od dożywotniego mija się z celem. Zdarzenie niesie `expiresAt`, a mail (ADR 0013) mówi „wygasa za 7 dni" albo — od teraz wprost — „ban jest bezterminowy". Skrzynka w aplikacji pozostaje płaska (ADR 0012), więc mail jest dziś jedynym kanałem z tą informacją.
- **Doba jako najdrobniejsza jednostka.** „Przerwa do jutra" to najkrótszy ban, który jeszcze coś znaczy; godziny kusiłyby do banów-prztyczków. Górnej granicy nie ma — bardzo długi ban czasowy to po prostu gorsza pisownia bezterminowego i niczym nie grozi.

## Konsekwencje

- **Zmiana addytywna dla API**: nowe opcjonalne pole w żądaniu, nowe nullable pole w odpowiedzi; istniejące klienty działają bez zmian. Istniejące bany w bazie pozostają bezterminowe bez żadnego backfillu.
- **Brak powiadomienia o wygaśnięciu.** Leniwe wygasanie nie ma chwili, w której cokolwiek się dzieje — powiadomienie wymagałoby zadania skanującego świeżo wygasłe bany. Mail przy nałożeniu niesie termin, więc zbanowany wie, kiedy wraca; to wystarcza, dopóki ktoś nie poprosi o „przypomnienie o powrocie".
- **Zbanowany w aplikacji nadal nie widzi terminu** — błąd `BANNED_FROM_COMMUNITY` nie niesie `expiresAt`, a płaski model powiadomień go nie przenosi. Naturalne miejsce (endpoint statusu członkostwa) to ta sama przyszła robota co ścieżka odwoławcza (ADR 0012).
- **Wygasły ban znika z listy banów** — lista pokazuje stan, nie historię. Moderator nie odróżni „nigdy nie był zbanowany" od „ban wygasł"; to wróci jako wymaganie razem z historią moderacji, jeśli kiedyś powstanie.
