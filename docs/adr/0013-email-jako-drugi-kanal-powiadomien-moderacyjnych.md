# 13. E-mail jako drugi kanał powiadomień o decyzjach moderacyjnych

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0012 dał powiadomienia o decyzjach moderacyjnych, ale wyłącznie w skrzynce w aplikacji — i sam zapisał w konsekwencjach granicę tego kanału: **moderator dowiaduje się o zgłoszeniu przy następnym wejściu do aplikacji**. Dla moderatora, który do aplikacji nie zagląda, to żadna różnica względem stanu sprzed ADR 0012: kolejka zgłoszeń dalej czeka na kogoś, kto sam z siebie nie ma powodu przyjść. Ten sam ADR wskazał też kierunek: kanał e-mail istnieje od resetu hasła (`MailSender`, ADR 0008) i jest tańszym pierwszym krokiem niż push (tokeny urządzeń, zgody, koszt).

## Decyzja

Wszystkie cztery typy z `NotificationType.MODERATION` wychodzą **dodatkowo e-mailem**, per zdarzenie, z modułu notification (`ModerationNotificationMailer`). Zwykła aktywność (lajki, komentarze, zaproszenia) mailem nie wychodzi.

Nowe interfejsy publiczne:

- `UserEmailDirectory` (identity) — adresy e-mail po `userId`. Do tej pory adres znał wyłącznie identity i wszystkie maile wychodziły z niego; teraz wysyła też notification.
- `CommunityDirectory` (community) — nazwa społeczności po identyfikatorze, do treści wiadomości.

`PostReportDismissedEvent` dostał `communityId` — jako jedyne z czterech zdarzeń go nie niósł, a mail musi nazwać społeczność.

## Uzasadnienie

- **Mailem wychodzą wyłącznie typy MODERATION i to nie jest kwestia oszczędności.** Skrzynka ukrywa powiadomienia od zablokowanych **przy odczycie** (Claude.md §9) — maila nie da się ukryć po fakcie. Mailowanie zwykłej aktywności omijałoby więc filtr blokad bocznymi drzwiami: lajk od zablokowanego zniknąłby ze skrzynki, ale leżałby w poczcie. Typy moderacyjne przechodzą przez filtr blokad zawsze (ADR 0012), więc dla nich — i tylko dla nich — oba kanały mówią dokładnie to samo. Granica kanału e-mail pokrywa się z granicą wyjątku `MODERATION` nieprzypadkowo i tak ma zostać.
- **Ograniczenie liczby wiadomości mamy za darmo.** Bramka „tylko pierwsze zgłoszenie, tylko nowy ban" siedzi u publikującego zdarzenie (ADR 0012), więc obowiązuje wszystkie kanały naraz. Digest, kolejkowanie i zbijanie duplikatów byłyby rozwiązaniem problemu, którego architektura zdarzeń już nie ma.
- **Mailer siedzi w notification, nie w community/content.** Moduły domenowe publikują decyzję; co z nią zrobić — zapis do skrzynki, mail, kiedyś push — to w całości sprawa modułu notification. Rozrzucenie maili po modułach decydujących zdublowałoby też regułę „nikt nie zawiadamia siebie" i fan-out do moderatorów.
- **Osobny listener obok `NotificationEventListener`, nie dopisek w nim.** Kanały mają być niezależne: awaria zapisu do skrzynki nie może zatrzymać maila ani odwrotnie. To ten sam wzorzec co mailery w identity — mailer jest listenerem `AFTER_COMMIT`, bo mail o banie, który się nie zacommitował, to informacja o zdarzeniu, którego nie było.
- **Błąd wysyłki nie leci dalej i jest łapany per adresat.** Powiadomienie w skrzynce już istnieje, więc przepadnięcie maila degraduje kanał, nie informację; jeden niedoręczalny adres nie może zatrzymać maili do pozostałych moderatorów. W logu wyłącznie `userId`, nigdy adres (Claude.md §13).
- **Adres e-mail wychodzi z identity przez interfejs i tylko do wysyłki.** `UserEmailDirectory` dokumentuje wprost: adres to dane wrażliwe, konsument przekazuje go do `MailSender.send` i nie wolno mu go logować ani zapisywać. Konwencja jak w `ProfileDirectory`: brakujące `userId` (konto usunięte między decyzją a listenerem) po prostu nie występują w wyniku i cicho wypadają z wysyłki.

## Konsekwencje

- **Brak opt-outu.** To wiadomości o decyzjach dotyczących konta w usłudze, nie marketing — a wolumen jest z konstrukcji niski (bramki na zdarzeniach). Model preferencji powiadomień (per typ, per kanał) to osobna robota; gdy powstanie, ten mailer jest jedynym miejscem do podpięcia.
- **Wiadomości nie zawierają linków do aplikacji.** Maile identity niosą linki, bo niosą tokeny; tu link byłby nawigacją, a struktura URL-i frontendu dla społeczności nie istnieje. Do dodania, gdy powstanie.
- **Nowy typ w `MODERATION` nie zaczyna mailować sam z siebie** — trzeba świadomie dopisać listener w `ModerationNotificationMailer`. To ta sama zasada co przy samym zbiorze `MODERATION` (ADR 0012): domyślną odpowiedzią jest „nie".
- **Treść maila jest uboższa niż skrzynka**: nie nazywa moderatora (skrzynka pokazuje aktora) ani powodu bana — mail ma sprowadzić do aplikacji, nie ją zastąpić.
- **Push pozostaje osobną decyzją.** E-mail zamyka lukę „moderator nieobecny w aplikacji"; push miałby sens dopiero jako kanał niskolatencyjny dla wszystkich typów, z pełnym modelem zgód.
