# 37. Linki z maili w schemacie `songly://`

Data: 2026-10-18
Status: zaakceptowane

## Kontekst

Mail z linkiem weryfikacyjnym (ADR 0009) i resetującym hasło (ADR 0008) kierował domyślnie na `http://localhost:3000/...`, czyli adres przyszłej aplikacji webowej (faza 3, poza tym repo). Takiej aplikacji nie ma. Jedynym klientem jest mobilka, a ta nie miała jak przechwycić linku: użytkownik musiał go skopiować z maila i wkleić na ekranie rejestracji albo resetu. ADR 0009 zapisał deep link jako jedyną część weryfikacji, której backend „nie jest w stanie dokończyć sam”.

Były dwie drogi:

| | App Links (`https://<domena>/verify-email`) | Własny schemat (`songly://verify-email`) |
|---|---|---|
| Wymaga | domeny i pliku `assetlinks.json` / `apple-app-site-association` na niej | nic |
| Klikalność w poczcie | zawsze — to zwykły `https` | zależy od klienta; część (m.in. Gmail) nie robi linku z nieznanego schematu |
| Bez zainstalowanej aplikacji | otwiera stronę | nic się nie dzieje |

## Decyzja

Domyślne `EMAIL_VERIFICATION_URL` i `PASSWORD_RESET_URL` to `songly://verify-email?token={token}` i `songly://reset-password?token={token}`. Aplikacja Android przechwytuje oba adresy.

Maile mówią wprost, co zrobić, gdy link się nie otwiera: skopiować go i wkleić w aplikacji. Wklejanie zostaje pełnoprawną ścieżką, a nie zapomnianą furtką. Klient wyciąga token z dowolnego adresu (`LinkToken`), więc nie zależy od schematu.

## Uzasadnienie

- **Domeny nie ma**, a decyzja o niej to decyzja produktowa, nie techniczna. Schemat działa od razu.
- **Koszt jest ograniczony do wygody.** Gdy klient poczty nie podlinkuje adresu, użytkownik nie traci możliwości dokończenia rejestracji, tylko robi to wklejeniem, jak do tej pory. Gorzej niż wcześniej nie jest dla nikogo, a lepiej dla każdego, czyj klient poczty schemat obsługuje.
- **Format linku zostaje w konfiguracji.** Przejście na App Links to zmiana dwóch zmiennych środowiskowych plus intent filter w aplikacji, bez zmian w kodzie backendu.

## Konsekwencje

- **Schemat nie jest zastrzeżony i to jest główny koszt tej decyzji.** Złośliwa aplikacja zainstalowana na telefonie ofiary może zadeklarować `songly://` i dostać token. Wtedy:
  - token weryfikacyjny wymieniony przez nią daje **jej** sesję na świeżo założonym koncie,
  - tokenem resetu ustawia **własne** hasło, czyli przejmuje konto.

  Ograniczenia: atak wymaga aplikacji już zainstalowanej u ofiary; gdy schemat deklarują dwie aplikacje, Android pyta użytkownika, którą otworzyć; token jest jednorazowy i krótko żyje (reset 30 min). Ryzyko jest więc wąskie, ale nie zerowe. App Links je zamykają, bo domena poświadcza, która aplikacja może otwierać jej adresy. **Gdy pojawi się domena, przejście na App Links jest poprawką bezpieczeństwa, a nie tylko wygody.**
- **Aplikacja webowa (faza 3) będzie potrzebowała `https`.** Wtedy właściwym ruchem jest jedna domena obsługująca oba światy: App Links otwierają aplikację, a bez niej ten sam adres otwiera stronę.
- Na Render obie zmienne mają `sync: false`. Jeśli ustawiono je ręcznie na stare adresy, trzeba je wyczyścić, inaczej nadpiszą nowy domyślny.
