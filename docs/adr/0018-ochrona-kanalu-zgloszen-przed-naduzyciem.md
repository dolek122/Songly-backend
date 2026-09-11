# 18. Ochrona kanału zgłoszeń przed nadużyciem

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0011 dał zgłoszenia postów, ADR 0012 powiadomienie dla **wszystkich** moderatorów przy pierwszym zgłoszeniu posta, ADR 0013 dorzucił do tego maila. Każdy z tych kroków miał swoją bramkę anty-spamową i każda z nich liczy **per post**:

- jedno zgłoszenie na parę (post, zgłaszający) — chroni licznik zgłoszeń przed uporem jednej osoby,
- powiadomienie i mail tylko przy **pierwszym** zgłoszeniu danego posta — chroni przed dwudziestoma osobami zgłaszającymi ten sam post.

**Żadna nie ogranicza jednej osoby w poprzek wielu postów.** Jedno konto zgłaszające 50 różnych postów legalnie przechodzi obie bramki: 50 wpisów w kolejce i 50 × liczba moderatorów maili. Do tego społeczności są publiczne, więc zgłaszać mógł każdy zalogowany, a **ban tego nie blokował** — `reportPost` nie sprawdzał banu.

Przed ADR 0013 skutkiem był zapchany widok kolejki. Po nim to poczta w skrzynce, której adresat nie może wyłączyć — czyli dokładnie sytuacja opisana w Claude.md §9 przy resecie hasła: *„rate limiting per IP sam nie wystarcza: ofiara zasypywania pocztą jest jedna — bez odstępu endpoint byłby darmowym mailerem wycelowanym w cudzą skrzynkę"*. Ten sam argument, inny endpoint; `RateLimitingFilter` nie obejmuje ścieżek za JWT (§9) i z założenia liczy per IP, nie per użytkownik.

## Decyzja

Trzy warstwy, każda zamykająca inną drogę:

1. **Ban blokuje zgłaszanie** — `reportPost` sprawdza `CommunityMembership.isBanned`, jak ścieżki publikowania i komentowania.
2. **Limit zgłoszeń per osoba** — `songly.content.report.limit` (domyślnie 20) w oknie przesuwanym `songly.content.report.window` (1 h), liczony zapytaniem po `post_reports`. Przekroczenie: 429 `REPORT_RATE_LIMIT_EXCEEDED`.
3. **Odstęp między mailami do moderatorów** — `songly.notification.moderation-mail.moderator-interval` (15 min) per społeczność i rodzaj sprawy (zgłoszenie / odwołanie), klucz w Redisie. Dotyczy **wyłącznie poczty**.

## Uzasadnienie

- **Trzy warstwy, bo każda sama w sobie jest niewystarczająca.** Ban odcina osobę już osądzoną, ale każdy inny użytkownik dalej może zgłaszać. Limit per osoba bounduje jedno konto, ale **nie chroni skrzynki**: dziesięcioro umówionych ludzi po dwa zgłoszenia daje dwadzieścia maili, a ofiarą jest jedna skrzynka niezależnie od tego, ilu nadawców ją zapełnia. Dopiero odstęp per społeczność bounduje to, co realnie boli. W drugą stronę: sam odstęp na mailach zostawiłby kolejkę do zasypania, bo tam nie ma żadnego limitu.
- **Ban blokuje zgłaszanie, bo zgłoszenie jest ścieżką docierającą do ludzi.** Claude.md §8 rysuje granicę banu: odcinamy publikowanie i komentowanie, zostawiamy lajki i udostępnienia, „bo to czyste liczby bez tożsamości". Zgłoszenie niesie tożsamość zgłaszającego i wolny tekst (`details`) wprost na biurko moderatora, a od ADR 0013 również do jego skrzynki pocztowej. Po tej granicy jest jednoznacznie po stronie odciętej. **Koszt:** zbanowany nie zgłosi spamu, który zobaczy — akceptujemy, bo społeczność ma innych czytelników, a osoba już osądzona nie powinna zachowywać kanału do moderatorów. Własną sprawę niesie ścieżka odwoławcza (ADR 0017), której ban nie dotyczy.
- **Limit per osoba liczony w bazie, nie w Redisie.** `post_reports` ma `created_at`, więc liczba zgłoszeń w oknie to jedno zapytanie — bez nowego stanu, bez rozjazdu między licznikiem a rzeczywistością i w tej samej transakcji co zapis. Przy okazji okno wychodzi **przesuwane**, więc znika efekt progu na granicy okna, który ma fixed-window w `RateLimitingFilter`. To jest bramka domeny, nie infrastruktury — i dlatego leży w module content, a nie w filtrze.
- **Domyślne 20/h jest celowo wysokie.** Ma odciąć zasypywanie, nie sumiennego człowieka zgłaszającego serię spamu podczas nalotu. Limit, który przeszkadza użytkownikom działającym w dobrej wierze, zostaje wyłączony przy pierwszej skardze i przestaje chronić przed czymkolwiek.
- **Wyciszamy pocztę, nie kolejkę i nie skrzynkę w aplikacji.** Ten mail nigdy nie niósł treści — mówi „jest po co zajrzeć" (ADR 0013, wzmocnione w 0017: żadnych cudzych tekstów w kanałach push). Skoro tak, drugi mail w ciągu kwadransa nie dodaje **żadnej** informacji: kolejka i tak pokazuje wszystko, co przyszło w międzyczasie. Wyciszony mail przepada i nie jest kolejkowany, bo kolejkowanie przywróciłoby dokładnie ten wolumen, który usuwamy. Powiadomienia w aplikacji zostają per post: są odwracalne (można je odczytać), niosą *który* post, a skrzynka jest miejscem na wolumen.
- **Fail-open przy awarii Redisa** — jak `RateLimitingFilter` (§9). Wyciszanie jest zabezpieczeniem, nie warunkiem poprawności: awaria cache'a nie może uciszyć kolejki moderacji.
- **Klucz per (społeczność, rodzaj sprawy)**, nie per moderator — zbiór adresatów jest ten sam dla całej społeczności, a rozdzielenie rodzajów pilnuje, żeby mail o odwołaniu nie przepadł tylko dlatego, że przed chwilą poszedł mail o zgłoszeniu.

## Konsekwencje

- **Zmiana zachowania istniejącego endpointu**: `POST /posts/{id}/reports` może teraz zwrócić 403 (ban) albo 429 (limit). Klient mobilny musi umieć pokazać oba — 429 najlepiej jako „spróbuj później", bez sugerowania, że zgłoszenie było błędne.
- **Nie ma `Retry-After` przy 429.** Okno jest przesuwane, więc uczciwa odpowiedź wymagałaby sprawdzenia, kiedy wygasa najstarsze zgłoszenie w oknie — dodatkowe zapytanie dla nagłówka, którego klient i tak nie potrzebuje do niczego poza komunikatem.
- **Brak metryki odrzuceń.** `songly.rate.limit.rejected{path}` liczy odrzucenia filtra, nie tej bramki; seria dla zgłoszeń wejdzie razem z metrykami moderacji, które i tak są następnym tematem (kilka decyzji z ADR 0012–0017 czeka na dane).
- **Zasypanie samej kolejki przez wielu użytkowników pozostaje możliwe** — dziesięciu ludzi po 20 zgłoszeń to 200 wpisów. Poczta jest już bounded, więc szkoda ogranicza się do widoku, który moderator może przejrzeć; realnym rozwiązaniem byłoby zaufanie do zgłaszających (waga zgłoszenia zależna od historii), co ma sens dopiero przy danych o tym, ile zgłoszeń bywa trafnych.
