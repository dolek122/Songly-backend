# 36. Nazwy użytkowników dla administracji

Data: 2026-10-17
Status: zaakceptowane

## Kontekst

Wykazy administracyjne niosą same identyfikatory:

| Wykaz | Moduł | Dlaczego bez nazw |
|---|---|---|
| `GET /admin/suspensions` | `identity` | `identity` nie może zależeć od `profile` — `profile` zależy od `identity`, więc powstałby cykl (reguła 2 `ModuleBoundariesTest`) |
| `GET /admin/admins` | `identity` | jw. (ADR 0021 zapisał to wprost) |
| `GET /admin/moderation-log` | `moderationlog` | dziennik z założenia przechowuje identyfikatory (ADR 0024) |

Klient dociągał nazwy przez `GET /profiles/{id}`. Dla zawieszonych to nie działa: ich profil jest ukryty przed wszystkimi (404, ADR 0026), także przed administratorem. Skutek był odwrotny do potrzeby. Nazwy brakowało dokładnie tam, gdzie administrator podejmuje decyzję: w wykazie zawieszeń, który jest zarazem kolejką odwołań (ADR 0023). Panel w mobilce pokazywał „konto 1a2b3c4d…”.

## Decyzja

`GET /api/v1/admin/profiles?userIds=…` w module `profile` zwraca `ProfileSummaryResponse(userId, displayName, avatarUrl, suspended)`, **bez filtra zawieszeń**.

- **Kontroler żyje w module, do którego należą dane, pod prefiksem `/api/v1/admin/**`.** Rolę pilnuje `SecurityConfig` (ADR 0021), tak samo jak przy `CommunityAdminController` (ADR 0028). Nowy kod nie potrzebuje ani jednego sprawdzenia roli.
- **Wsadowo, nie po jednym.** Wykaz zawieszeń to jedno żądanie zamiast N.
- **Limit 100 id** (`TOO_MANY_USER_IDS`). Wykazy mają po kilka–kilkadziesiąt pozycji, a bez limitu endpoint byłby zrzutem całej tabeli profili jednym żądaniem.
- **Nieznane id pomijamy**, nie zwracamy błędu: usunięte konto w historii moderacji to normalny przypadek.
- **Flaga `suspended`** mówi administratorowi, że ogląda coś, czego reszta serwisu nie widzi.

## Uzasadnienie

**Ukrywanie zawieszonych (ADR 0026) chroni innych użytkowników przed treściami, za które ktoś został zawieszony.** Administratorowi zasłaniało wyłącznie to, kogo dotyczy jego własna decyzja. Wyjątek dla administracji nie osłabia więc ochrony, tylko przywraca jej sens.

Rozważone i odrzucone:

- **Nazwa w `SuspendedAccountResponse`.** Wymagałaby od `identity` sięgnięcia do `profile`, czyli cyklu. Odwrócenie zależności (profil wypycha nazwy do `identity`) dublowałoby dane między modułami.
- **Wyjątek dla admina w `GET /profiles/{id}`.** Rozmywałby prostą regułę „zawieszony = 404” z ADR 0026 na publicznym endpoincie, a klient i tak robiłby N żądań.

## Konsekwencje

- Panel administratora pokazuje nazwy zawieszonych, a lista adminów i dziennik dostają nazwy jednym żądaniem.
- Bio i gatunki zawieszonego zostają ukryte także przed adminem. Do decyzji o odwołaniu wystarczy wiedzieć, o kim mowa; gdyby administracja potrzebowała więcej, to osobna decyzja o osobnym zakresie danych.
