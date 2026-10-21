# 46. Domyślna nazwa wyświetlana bez adresu e-mail

Data: 2026-10-21
Status: zaakceptowane

## Kontekst

Profil powstaje z `UserRegisteredEvent` i od początku dostawał nazwę wyświetlaną równą części adresu przed „@”. Nazwa jest publiczna: widać ją na profilu, w feedzie, w komentarzach, w powiadomieniach u innych i w wyszukiwarce użytkowników, która szuka po fragmencie nazwy. U większości ludzi ta część adresu to imię i nazwisko albo stały pseudonim, więc każdy zalogowany dostawał połowę cudzego adresu e-mail, a resztę często dało się zgadnąć (gmail.com).

Kłóciło się to z zasadami, które pilnujemy gdzie indziej: adresy nie trafiają do logów (§13), rejestracja nie zdradza, czy adres ma konto (ADR 0009), a adres wydaje innym modułom wyłącznie `UserEmailDirectory` i tylko do wysyłki (ADR 0013). Zdarzenie `UserRegisteredEvent` niosło przy tym adres do każdego słuchacza wyłącznie po to, żeby profil zrobił z niego nazwę.

## Decyzja

- Domyślna nazwa to **„Użytkownik ” i sześć losowych cyfr** (`ProfileService.defaultDisplayName`). Użytkownik zmienia ją jak dotąd przez `PUT /api/v1/profiles/me`.
- `UserRegisteredEvent` **traci pole `email`**. Zostają `userId` i `occurredAt`.
- **Istniejących profili nie zmieniamy.** Migracja nie odróżni nazwy, której ktoś nigdy nie zmienił, od nazwy, którą świadomie ustawił na swój login. Przemianowanie drugiej grupy na „Użytkownik 123456” odebrałoby ludziom wybraną nazwę bez pytania.
- Pomocnik testowy `registerAndVerify` nadaje kontu nazwę z adresu jawnie, przez `PUT /profiles/me`. Domyślną nazwę sprawdza `DefaultDisplayNameIntegrationTest`, który z pomocnika nie korzysta.

## Uzasadnienie

- **Losowa liczba, a nie nic.** Puste pole albo samo „Użytkownik” wyglądałoby w wyszukiwarce i w komentarzach jak jedna osoba. Sufiks pozwala odróżnić konta, zanim ich właściciele wybiorą nazwy.
- **Sześć cyfr.** Nazwy nie są unikalne i nie muszą być. Przy czterech cyfrach kilka tysięcy kont z domyślną nazwą dawałoby w wyszukiwarce długie listy identycznych wpisów.
- **Bez identyfikatora konta w nazwie.** Fragment UUID wyglądałby podobnie, ale łączyłby nazwę z identyfikatorem z API i logów, czyli tworzyłby powiązanie, którego dziś nie ma.
- **Usunięcie pola ze zdarzenia, a nie tylko zmiana nazwy.** Pole, z którego nikt nie korzysta, zachęca, żeby następny słuchacz z niego skorzystał. Zdarzenia domenowe trafiają do analityki (ADR 0004), więc to miejsce, w którym adresu nie chcemy.

## Konsekwencje

- Kontrakt API się nie zmienia, zmienia się tylko wartość nazwy nowych kont. Klient mobilny warto uzupełnić o zachętę do ustawienia nazwy po pierwszym logowaniu.
- Konta założone przed tą zmianą mają nadal nazwy z adresu, dopóki ich właściciele ich nie zmienią. Jeśli przed publicznym startem zostaną na serwerze prawdziwe konta, trzeba zdecydować osobno, czy je przemianować.
- Testy integracyjne zużywają jeden zapis na konto więcej (`PUT /profiles/me` w pomocniku). Dotyczy to tylko testu limitu zapisów per użytkownik, który ma ten zapis wliczony.
