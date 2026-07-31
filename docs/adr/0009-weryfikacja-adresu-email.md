# 9. Weryfikacja adresu e-mail: konto powstaje dopiero po potwierdzeniu

Data: 2026-07-31
Status: zaakceptowane

## Kontekst

ADR 0008 zamknął dwie wyrocznie na istnienie kont (reset hasła) i zostawił trzecią otwartą, wprost to odnotowując: `POST /api/v1/auth/register` odpowiadał `409 EMAIL_ALREADY_USED` z adresem w treści komunikatu. Wystarczyło wpisywać kolejne adresy, żeby wylistować, kto ma konto w Songly — mimo że logowanie (Claude.md §13) i reset hasła (§9) bronią się przed tym z osobna, każde własnym kosztem.

Drugi problem był bardziej podstawowy: **nikt nie sprawdzał, czy rejestrujący ma dostęp do podanej skrzynki**. Można było założyć konto na cudzy adres. Po ADR 0008 to przestało być tylko nieuprzejmością: właściciel tego adresu mógł zamówić reset hasła i przejąć konto, którego nigdy nie zakładał — a osoba, która je zakładała, straciłaby je bez śladu.

Kanał e-mail powstał w ADR 0008 i był brakującym warunkiem, żeby dało się to rozwiązać.

## Decyzja

Rejestracja **odkłada** konto zamiast je zakładać:

- `POST /api/v1/auth/register` → **202 zawsze**, bez tokenów. Zapisuje wiersz w nowej tabeli `pending_registrations` (migracja `V15`) i wysyła link potwierdzający.
- `POST /api/v1/auth/register/verify` → 200 z parą tokenów. Dopiero tutaj powstaje wiersz w `users` i leci `UserRegisteredEvent`.

Adres zajęty i adres wolny dają **identyczną odpowiedź**. Różnica jest wyłącznie w treści wiadomości, którą dostaje właściciel skrzynki: wolny adres dostaje link potwierdzający, zajęty — ostrzeżenie „ktoś próbował założyć konto na Twój adres" kierujące do resetu hasła.

Token: 256 losowych bitów, w bazie sam skrót SHA-256, TTL 24 h, jednorazowy, jeden na adres. `EmailAlreadyUsedException` znika z kodu.

## Uzasadnienie

- **Osobna tabela, a nie flaga `email_verified` na `users`** — to jest decydujący wybór tego ADR-u. Niezweryfikowany wiersz w `users` dostawałby profil (`UserRegisteredEvent`), pojawiałby się w wyszukiwarce użytkowników i w dopasowywaniu po gatunkach, więc **każdy moduł musiałby nauczyć się rozróżniać „potwierdzony" od „nie"** — dokładnie ten rodzaj stanu, który rozlewa się po całej aplikacji i po roku nie da się go już usunąć (Claude.md §2). Przy odłożonym wierszu żaden moduł poza `identity` w ogóle nie widzi tego stanu; `UserRegisteredEvent` po prostu leci później, i to jest prawda, bo wcześniej konto nie istniało. **Zmiana nie dotyka ani jednej linii poza `identity`.**
- **Brak FK do `users` jest cechą, nie brakiem.** W chwili istnienia wiersza `pending_registrations` konto jeszcze nie istnieje, więc ta tabela — jako jedyna nowa tabela per-użytkownik od dawna — **nie potrzebuje listenera kasowania konta** (§9). Pułapka, o którą potyka się każda inna, tutaj nie występuje z definicji.
- **Ponowna rejestracja wydaje nowy link, ale nie podmienia hasła.** To zabezpieczenie, nie niedoróbka: podmiana pozwoliłaby komuś obcemu wstrzelić własne hasło w cudze, jeszcze niepotwierdzone zgłoszenie i poczekać, aż właściciel skrzynki kliknie najnowszy link — konto powstałoby z hasłem napastnika. Kto pomylił się przy pierwszej rejestracji, potwierdza adres i zmienia hasło zwykłą drogą.
- **Hasło hashujemy także w gałęzi „adres zajęty"**, mimo że nie zapisujemy go tam. Bcrypt kosztuje rzędy wielkości więcej niż zapytanie o e-mail, więc bez tego **czas odpowiedzi sam odtworzyłby wyrocznię**, którą reszta metody likwiduje. Równość statusów HTTP bez równości kosztu jest ochroną pozorną.
- **Potwierdzenie od razu wydaje tokeny.** Kliknięcie w link dowodzi kontroli nad skrzynką, a hasło podał ten sam człowiek chwilę wcześniej — osobne logowanie zaraz potem nie sprawdzałoby niczego więcej.
- **Ostrzeżenie dla właściciela zajętego adresu** jest ceną za milczenie wobec pytającego. Skoro rejestracja nie może odpowiedzieć „ten adres jest zajęty", nie wolno jej też po prostu zamilknąć — informację dostaje jedyna osoba, która ma prawo ją znać. Wiadomość kieruje do resetu hasła, bo to jedyna sensowna reakcja w obu przypadkach: własnej pomyłki i cudzej próby.
- **TTL 24 h, a nie 30 min jak przy resecie.** Reset robi się w reakcji na problem, więc użytkownik siedzi wtedy przy skrzynce; rejestrację często zaczyna się „na później". Link i tak nie otwiera niczego, co już istnieje.
- **Odstęp między wysyłkami per adres** — tu ważniejszy niż przy resecie, bo tam wysyłka wymaga istniejącego konta, a tutaj wystarczy dowolny adres. Rate limiting per IP tego nie łapie: ofiara jest jedna, adresów źródłowych dowolnie wiele.

## Konsekwencje

- **Breaking change w publicznym API.** `POST /register` zwraca 202 bez ciała zamiast 201 z tokenami, a `409 EMAIL_ALREADY_USED` znika. Klient mobilny (osobne repo `Songly-mobile`) wymaga zmiany: po rejestracji pokazuje ekran „sprawdź skrzynkę", obsługuje deep link `EMAIL_VERIFICATION_URL` i woła `/register/verify`. **To jedyna część tej zmiany, której backend nie jest w stanie dokończyć sam.**
- **Istniejące konta zostają nietknięte** — migracja `V15` nie ma ani jednego `ALTER TABLE users`. Konta powstałe zanim weryfikacja istniała działają dalej; wymaganie jej wstecz wylogowałoby wszystkich naraz.
- Testy: rejestracja przestała być jednym żądaniem, więc `AbstractIntegrationTest` dostał `registerAndVerify(...)` i **globalnie wpiętą atrapę kanału e-mail** — token nie istnieje w postaci jawnej nigdzie poza wysłaną wiadomością. To nie jest obejście weryfikacji: każdy z tych testów naprawdę przechodzi pełną ścieżkę, łącznie z odczytaniem linku.
- Przy okazji wyszła granica, o którą suita zaraz by się potknęła: każda kombinacja `@TestPropertySource` to osobny buforowany kontekst Springa z własną pulą Hikari, a przy domyślnych 10 połączeniach kilkanaście kontekstów wyczerpuje `max_connections` Postgresa. Objaw jest mylący — pojedyncza klasa przechodzi, a pełny przebieg wywala losową inną klasę na „Failed to load ApplicationContext". Stąd pula ograniczona do 4 w klasie bazowej.
- Wydawanie sesji przeniosło się z `AuthService` do `TokenIssuer`, bo weryfikacja stała się drugim miejscem, które wydaje tokeny.
- Nowa metryka `songly.auth.registration.attempts{outcome}` — serie `requested`, `pending`, `resent`, `throttled`, `existing-account`, `verified`, `invalid-token`. `existing-account` rosnące ponad tło to sygnał, że ktoś przeczesuje adresy, mimo że odpowiedzi mu tego nie ułatwiają. Seria nie mówi **jakiego** adresu dotyczy, konsekwentnie z §13.
- Świadomie **poza zakresem**: sprzątanie porzuconych zgłoszeń. Wiersz jest jeden na adres i nadpisywany przy ponownej rejestracji, więc tabela rośnie najwyżej liniowo z liczbą różnych adresów, na które ktoś kiedykolwiek próbował się zarejestrować. Gdy to zacznie być widoczne, dojdzie zadanie kasujące wygasłe zgłoszenia — wzorowane na `SessionCleanupJob`, bez żadnych nowych decyzji.
