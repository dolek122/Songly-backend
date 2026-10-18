# 40. Powiadomienia push

Data: 2026-10-18
Status: zaakceptowane

## Kontekst

Powiadomienia docierały dwoma kanałami: skrzynką w aplikacji i mailem, ale mail obejmuje wyłącznie decyzje moderacyjne (ADR 0013). Zwykła aktywność, czyli lajk, komentarz czy zaproszenie, czekała w skrzynce, aż ktoś sam otworzy aplikację. Plakietka nieprzeczytanych odświeżała się tylko przy wejściu na feed.

Push ma ten sam problem co mail: nie da się go ukryć po fakcie. Skrzynka ukrywa powiadomienia od zablokowanych przy odczycie (Claude.md §9), a push leży już na telefonie. Dlatego mail ograniczyliśmy do moderacji. Push ograniczony tak samo nie przyszedłby jednak prawie nikomu, czyli byłby funkcją martwą.

## Decyzja

**Zakres:** pushem wychodzą wszystkie typy powiadomień. Filtr blokad działa w chwili wysyłki: blokada w dowolną stronę wycisza push. Wyjątkiem są typy `MODERATION`, które przechodzą mimo blokady, tak jak w skrzynce (ADR 0012).

**Treść:** typ i nazwa aktora, np. tytuł „Ola” i treść „Nowy komentarz pod Twoim postem”. Nie wysyłamy tekstu komentarza, powodów sankcji ani treści odwołań. Przy decyzjach moderacyjnych zamiast aktora stoi nazwa społeczności, bo ban należy do społeczności, a nie do moderatora (Claude.md §8). Przy zgłoszeniu posta nie wskazujemy zgłaszającego.

**Mechanika:**
- `NotificationService.record` publikuje wewnętrzne `NotificationCreatedEvent` tylko przy **nowym** wierszu. Push dziedziczy więc za darmo deduplikację z ADR 0035: pętla lajk/cofnij nie dzwoni przy każdym obrocie, a zwinięte zaproszenie nie dzwoni drugi raz.
- `PushNotifier` wysyła po commicie tej transakcji, w wątku żądania, jak mailer. Błąd jednego urządzenia nie zatrzymuje pozostałych i nie wraca do wywołującego.
- `PushSender` w `common.push` to infrastruktura bez wiedzy o domenie. Domyślnie `provider=log`, a `fcm` bez `FCM_CREDENTIALS_JSON` **wywala start**, tak jak `smtp` bez hosta.
- FCM HTTP v1 wołamy wprost przez `RestClient`. Z Google'a bierzemy tylko `google-auth-library` po token OAuth. Pełny `firebase-admin` ciągnąłby gRPC i Guavę dla jednego żądania POST.
- Android dostaje wiadomości wyłącznie z `data`, więc aplikacja sama buduje powiadomienie (kanał, akcja po stuknięciu) także w tle. Dla iOS dokładamy blok `apns.alert`, bo bez niego śpiąca aplikacja nie pokaże niczego.

**Urządzenia** (`push_devices`, kluczem jest token):
- `PUT /api/v1/notifications/push-devices` rejestruje urządzenie idempotentnie. Token znany pod innym kontem przepina się na nowe, bo to ten sam telefon, na którym ktoś się przelogował.
- `POST /api/v1/notifications/push-devices/removal` wyrejestrowuje urządzenie przed wylogowaniem. Cudzy token daje 204 bez efektu.
- Urządzenia **giną razem z sesjami**: `identity` publikuje `UserSessionsRevokedEvent` przy zmianie hasła, resecie hasła i wykryciu kradzieży tokenu.
- Token, na który FCM odpowie `UNREGISTERED`, znika. `INVALID_ARGUMENT` nie wystarcza, bo bywa błędem żądania, a kasowanie na tej podstawie mogłoby po cichu odpiąć zdrowe telefony.
- Najwyżej 10 urządzeń na konto. Nadmiar przycinamy od najdawniej zgłoszonych.

## Uzasadnienie

- **Filtr przy wysyłce zamiast samej moderacji:** blokada sprzed zdarzenia wycisza push. Push wysłany przed blokadą zostaje na telefonie, a to ten sam stan, co powiadomienie przeczytane przed blokadą. Ukrycie go byłoby możliwe tylko wtedy, gdyby nigdy nie wyszedł.
- **Urządzenie przy zmianie hasła:** zmiana hasła po kradzieży telefonu, która zostawia na nim pushe z nazwami znajomych, nie zamyka niczego. To ten sam argument, przez który zmiana hasła odwołuje wszystkie sesje (§9). Token push nie jest powiązany z sesją, bo access token nie niesie jej identyfikatora, więc odpinamy wszystkie urządzenia naraz.
- **`switch` bez `default` w doborze tekstu:** nowy typ powiadomienia nie skompiluje się, dopóki ktoś nie zdecyduje, co mówi jego push, także o tym, czy niesie nazwę aktora.

## Konsekwencje

- **Brak przełącznika po stronie serwera.** Pushe wyłącza się w ustawieniach systemu telefonu. Przełącznik w API ma sens dopiero, gdy będzie potrzebny podział na kategorie, jak przy mailach (ADR 0020).
- **Opóźnienie w żądaniu wywołującym:** lajk czeka na odpowiedź FCM dla każdego urządzenia autora posta. Timeouty są krótkie (2 s połączenie, 5 s odczyt). Przy realnym ruchu właściwym ruchem jest kolejka albo wirtualne wątki, nie dłuższe timeouty.
- **Wylogowanie zależy od klienta:** pojedynczy logout nie wie, które urządzenie należy do której sesji, więc wyrejestrowanie robi klient tuż przed nim. Klient, który tego nie zrobi, nadal dostaje pushe, dopóki nie odwołamy wszystkich sesji albo FCM nie zgłosi tokenu jako martwego.
- **Zawieszenia kont nie mają pushy**, bo nie mają też wpisów w skrzynce. Docierają mailem (ADR 0022).
- **iOS:** backend wysyła `apns.alert`, ale aplikacja iOS nie rejestruje jeszcze urządzeń. Wymaga to APNs, czyli konta Apple Developer i Maca.
- Metryka `songly.push.deliveries{outcome}`: `sent`, `invalid-token`, `failed`, `skipped-blocked`.
