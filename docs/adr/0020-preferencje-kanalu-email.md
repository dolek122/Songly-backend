# 20. Preferencje kanału e-mail

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0013 wypuścił maile o decyzjach moderacyjnych i świadomie nie dał opt-outu: „to wiadomości o decyzjach dotyczących konta w usłudze, nie marketing — a wolumen jest z konstrukcji niski. Model preferencji powiadomień (per typ, per kanał) to osobna robota; gdy powstanie, ten mailer jest jedynym miejscem do podpięcia."

Od tamtej pory kanał urósł: doszły maile o odwołaniach (ADR 0017), a wezwania do kolejki trafiają do **wszystkich** moderatorów społeczności. Moderator aktywnej społeczności dostaje więc pocztę, której nie zamawiał i nie może wyłączyć — a jedynym dostępnym „wyłącznikiem" byłoby oddanie roli.

## Decyzja

`GET`/`PUT /api/v1/notifications/mail-preferences` z dwoma przełącznikami, oba domyślnie włączone:

| Przełącznik | Co wycisza |
|---|---|
| `decisionsAboutMe` | ban, usunięcie mojego posta, rozstrzygnięcie mojego zgłoszenia albo odwołania |
| `moderationQueue` | wezwania do pracy moderacyjnej: nowe zgłoszenie, nowe odwołanie |

Preferencje rządzą **wyłącznie pocztą**. Skrzynka w aplikacji zostaje kompletna i nie podlega ustawieniom.

## Uzasadnienie

- **Skrzynki w aplikacji nie da się wyłączyć i to jest sedno tej decyzji.** Cała linia ADR 0012–0017 stoi na zdaniu „decyzji moderacyjnej nie da się odmówić" — dlatego typy `MODERATION` przechodzą mimo blokad, bo inaczej wystarczyłoby zablokować moderatora, żeby nie zobaczyć własnego bana. Przełącznik wyłączający powiadomienia w aplikacji odtwarzałby tę samą dziurę innym wejściem, a przy okazji podważał ścieżkę odwoławczą (ADR 0017): odwołać się można od decyzji, o której się wie. **Mail to co innego — to doręczenie do systemu poza naszą kontrolą**, więc tu wybór należy do adresata; zapis o decyzji zostaje tam, gdzie po niego trzeba przyjść. To ta sama asymetria push/pull, na której stoją ADR 0013, 0015 i 0017.
- **Dwie kategorie, nie przełącznik na każdy typ.** Piętnaście typów × kanały to ekran ustawień, którego nikt nie przejrzy, i decyzja o domyślnej wartości przy każdym nowym typie. Te dwie odpowiadają dwóm **rolom**, w jakich człowiek dostaje maila: jestem adresatem decyzji albo jestem wzywany do pracy. Rozdzielenie ich jest konieczne, bo moderator zasypywany pocztą ze swojej społeczności nie powinien przy okazji tracić informacji o własnym koncie — a to by się stało przy jednym wspólnym wyłączniku.
- **Opt-out, nie opt-in**, jak widoczność presence (ADR 0007): te maile są konsekwencją korzystania z usługi i pierwszy ban jest dokładnie tym momentem, w którym wiadomość musi dojść. Brak wiersza = zgoda; wiersz powstaje dopiero przy pierwszej zmianie, więc rejestracja niczego nie zakłada (ten sam wzorzec co `now_playing_preferences`).
- **Filtrujemy per adresat, nie „wyślij albo nie".** Mail o zgłoszeniu idzie do wszystkich moderatorów naraz, więc jeden wypisany nie może wyciszyć wysyłki pozostałym. Odstęp z ADR 0018 działa niezależnie i wcześniej: tamto okno należy do **społeczności**, to filtrowanie do **osoby**.
- **Maile modułu `identity` zostają poza tym całkowicie** — weryfikacja adresu (ADR 0009) i reset hasła (ADR 0008) to nie powiadomienia, tylko elementy uwierzytelniania. Możliwość ich wyłączenia oznaczałaby konto, do którego nie da się odzyskać dostępu. Stąd nazwa `mail-preferences` w module notification, a nie globalne „ustawienia poczty".
- **Endpoint nazywa się `mail-preferences`, a nie `preferences`** — nazwa ma mówić wprost, czego ustawienia **nie** obejmują. Ogólna nazwa sugerowałaby, że da się tu kiedyś wyłączyć skrzynkę, czyli dokładnie to, czego robić nie zamierzamy.
- **`PUT` zastępuje oba pola naraz** (oba `@NotNull`): pominięte pole znaczyłoby „zostaw jak było" i robiło z `PUT` po cichu aktualizację częściową.

## Konsekwencje

- **Nowa tabela per-user z FK do `users`**, więc `NotificationAccountDeletionListener` sprząta teraz dwie tabele (Claude.md §9). `AccountDeletionFlowIntegrationTest` ustawia preferencje przed usunięciem konta — bez tego wiersz by nie powstał i test nigdy nie zahaczyłby o ten klucz obcy.
- **Wyciszony mail przepada bez śladu w metrykach.** `songly.moderation.mail.suppressed` (ADR 0019) liczy wyłącznie wyciszenia odstępem, bo tamto jest awaryjnym zachowaniem systemu, a to zwykłą realizacją cudzej decyzji. Jeśli kiedyś trzeba będzie wiedzieć, ilu ludzi się wypisało, odpowiedzią jest zapytanie do tabeli, nie licznik zdarzeń.
- **Zwykła aktywność (lajki, komentarze) nie ma tu żadnego przełącznika, bo nie wychodzi mailem** (ADR 0013). Gdy kiedyś zacznie — a push jest wciąż osobną decyzją — ten model będzie wymagał trzeciej kategorii, i dopiero wtedy warto rozważyć granulację per typ.
- **Nie ma listy „wypisany z wszystkiego" ani obsługi twardych odbić.** Konto z niedziałającą skrzynką dalej generuje próby wysyłki; to zadanie dla dostawcy poczty, nie dla tej tabeli.
