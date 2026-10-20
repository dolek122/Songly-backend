# 42. Adres e-mail bez rozróżniania wielkości liter

Data: 2026-10-20
Status: zaakceptowane

## Kontekst

Adres był porównywany dosłownie. Klawiatura w telefonie zaczyna pole wielką literą sama z siebie, a autouzupełnianie dopisuje spację na końcu. Skutki:

- rejestracja jako `Jan@gmail.com` i logowanie jako `jan@gmail.com` kończyło się „błędnym hasłem”,
- na ten sam adres dało się założyć drugie konto inną wielkością liter,
- reset hasła wpisany inaczej niż przy rejestracji po cichu nie wysyłał niczego (202 bez maila, zgodnie z ADR 0008),
- spacja na końcu odpadała na `@Email` jako 400.

## Decyzja

- `RegisterRequest`, `LoginRequest` i `PasswordResetRequest` normalizują adres w konstruktorze rekordu: `strip()` i małe litery (`Locale.ROOT`). Walidacja działa już na postaci znormalizowanej.
- Migracja `V29` obniża litery istniejących adresów w `users` i `pending_registrations`, **z pominięciem kolizji**: jeśli dwa wiersze różnią się tylko wielkością liter, żaden nie jest ruszany.

## Uzasadnienie

- **Na granicy, nie w serwisach.** Każde zapytanie po adresie przychodzi z DTO, więc serwis nie ma jak zapomnieć o normalizacji. Normalizacja w serwisie przyszłaby też za późno, bo `@Email` odrzucałby spację, zanim serwis by ją zobaczył.
- **Małe litery wbrew literze RFC 5321.** Standard pozwala części lokalnej rozróżniać wielkość liter, ale żaden popularny dostawca tego nie robi. Ryzyko, że dwie różne skrzynki różnią się tylko wielkością liter, jest pomijalne wobec pewności, że ludzie wpisują ten sam adres różnie.
- **Migracja zamiast porównywania bez wielkości liter w zapytaniach.** `lower(email) = lower(?)` wymagałoby indeksu funkcyjnego i dalej dopuszczało dwa konta na jedną skrzynkę, bo `UNIQUE` zostałby dosłowny.
- **Kolizji nie rozstrzyga migracja.** Wybór, które z dwóch kont „wygrywa”, to decyzja o cudzych danych, a nie krok techniczny.

## Konsekwencje

- Jeśli na którymś środowisku istnieją pary kont różniące się wyłącznie wielkością liter, zostają nietknięte, a logowanie znajdzie tylko to zapisane małymi literami. Sprawdzenie:

  ```sql
  SELECT lower(email), count(*) FROM users GROUP BY lower(email) HAVING count(*) > 1;
  ```

- Wiadomości wychodzą na adres zapisany małymi literami, nie na wpisany przez użytkownika.
