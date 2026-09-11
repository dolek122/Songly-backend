# 12. Powiadomienia o decyzjach moderacyjnych

Data: 2026-08-04
Status: zaakceptowane

## Kontekst

ADR 0011 dał moderatorom narzędzia — bany, kasowanie cudzych postów, kolejkę zgłoszeń — i sam zapisał w konsekwencjach, czego nie dał: „nie ma powiadomień o decyzjach moderacyjnych". W praktyce znaczyło to, że **obie strony każdej decyzji zostają w ciemności**:

- **Zbanowany nie wie, że jest zbanowany.** Dowiaduje się przy próbie napisania czegokolwiek, czyli decyzja moderacyjna objawia mu się jako błąd aplikacji. Nie da się od niej odwołać ani jej zrozumieć, bo nie wygląda na decyzję.
- **Autorowi usuniętego posta post po prostu znika.** Bez informacji, że ktoś go usunął, najprostszym wnioskiem jest „aplikacja zgubiła mi treść" — i napisanie jej ponownie.
- **Zgłaszający nie dostaje nic.** Zgłaszanie jest wtedy wrzucaniem kartek do zamkniętej skrzynki; po kilku takich razach przestaje się zgłaszać cokolwiek, a kolejka wysycha.
- **Moderator nie wie, że ma co robić.** To najpoważniejsze: kolejka zgłoszeń działa **wyłącznie wtedy, gdy ktoś sam do niej zajrzy**. Zgłoszenie w społeczności, której moderator akurat nie odwiedza, leży bezterminowo. Narzędzie było, ale nic nie inicjowało jego użycia.

Moduł `notification` istniał już i obsługiwał powiadomienia o cudzej aktywności (lajki, komentarze, zaproszenia, dołączenia do społeczności), więc pytanie nie brzmiało „czym powiadamiać", tylko „czym decyzja moderacyjna różni się od zwykłej aktywności".

## Decyzja

Cztery nowe typy powiadomień, każdy wynikający z jednego zdarzenia domenowego publikowanego przez moduł, który podejmuje decyzję:

| Typ | Do kogo | Zdarzenie | Zasób |
|---|---|---|---|
| `COMMUNITY_BANNED` | zbanowany | `CommunityMemberBannedEvent` (community) | społeczność |
| `COMMUNITY_POST_REMOVED` | autor usuniętego posta | `CommunityPostRemovedEvent` (content) | społeczność |
| `COMMUNITY_POST_REPORTED` | **wszyscy** moderatorzy | `PostReportedEvent` (content) | post |
| `POST_REPORT_DISMISSED` | zgłaszający | `PostReportDismissedEvent` (content) | post |

Powiadomienia tych czterech typów (`NotificationType.MODERATION`) **przechodzą przez filtr blokad**, w odróżnieniu od całej reszty skrzynki.

## Uzasadnienie

- **Decyzja moderacyjna nie podlega blokadzie i to jest sedno tej zmiany.** Reszta skrzynki znika, gdy nadawca jest zablokowany w którymkolwiek kierunku (Claude.md §9) — i tak ma zostać, bo blokada odcina od zaczepek. Ale decyzji moderacyjnej nie da się odmówić: gdyby podlegała temu filtrowi, wystarczyłoby **zablokować moderatora, żeby nigdy nie zobaczyć informacji o własnym banie**. Blokada działałaby wtedy na szkodę blokującego, ukrywając przed nim dokładnie to, co powinien wiedzieć. Dokładnie tę ścieżkę utrwala `moderationNotificationSurvivesBlockingTheModerator`: zwykłe powiadomienie od tej samej osoby znika, informacja o banie zostaje.
- **Wyjątek jest przy odczycie, nie przy zapisie.** Nie oznaczamy powiadomień inaczej w bazie ani nie kasujemy niczego przy blokadzie — filtr po prostu przepuszcza typy z `MODERATION`. Ta sama zasada, co przy filtrze blokad w module content: **ukrywamy przy odczycie, bo blokada bywa zdejmowana**, a kasowanie jest nieodwracalne.
- **Powiadomić trzeba też licznik nieprzeczytanych i „oznacz wszystkie jako przeczytane".** Wszystkie trzy zapytania (`findVisible`, `findVisibleUnread`, `countVisibleUnread`) dostały ten sam warunek, bo rozjazd między nimi daje plakietkę z liczbą, której nie da się zbić: widać 1 nieprzeczytane, na liście nie ma czego kliknąć. `unreadCountMatchesWhatIsVisible` pilnuje tej zgodności.
- **Zapytania musiały przejść z metod wyprowadzonych na `@Query`.** Warunek jest alternatywą (`actor_id not in (...) OR type in (...)`), a nazwy metod Spring Data wyrażają tylko koniunkcję. To jedyny powód tej zamiany — nie optymalizacja.
- **Tylko pierwsze zgłoszenie danego posta zawiadamia moderatorów.** Adresatami są **wszyscy** moderatorzy, więc post zgłoszony przez dwadzieścia osób zasypałby trzech moderatorów sześćdziesięcioma powiadomieniami o jednej sprawie — i to dokładnie wtedy, gdy sprawa jest najpilniejsza. Liczbę niezależnych zgłoszeń niesie `reportCount` w kolejce (ADR 0011) i to on odpowiada na pytanie „ilu osobom to przeszkadza"; powiadomienie odpowiada tylko na „jest po co zajrzeć".
- **Tylko nowy ban zawiadamia zbanowanego.** Ban jest idempotentny (ADR 0011), więc bez tego warunku powtórzone kliknięcie moderatora dokładałoby kolejne powiadomienia o tym samym.
- **Skasowanie własnego posta nie jest decyzją moderacyjną.** Zdarzenie leci wyłącznie, gdy kasujący nie jest autorem — inaczej każdy dostawałby powiadomienie o tym, co przed chwilą sam zrobił.
- **Przy usuniętym poście zasobem jest społeczność, nie post.** Post w tym momencie przestaje istnieć, więc identyfikator, w który klient miałby kliknąć, prowadziłby donikąd. Przy zgłoszeniu i jego odrzuceniu post istnieje, więc zasobem jest post.
- **Zgłoszony post nie generuje powiadomienia dla swojego autora.** Zgłoszenie to wiadomość do moderatorów, nie do niego; ostrzeganie autora, że ktoś go zgłosił, byłoby zaproszeniem do odwetu wobec zgłaszającego, zanim ktokolwiek sprawę rozstrzygnie.
- **Adresatów podaje moduł community przez `CommunityMembership.moderatorIds`.** Notification nie ma prawa czytać tabeli członkostw (Claude.md §2). Interfejs zwraca identyfikatory, nie encje ani DTO — konsument potrzebuje wyłącznie adresatów. `CommunityRole.MODERATING` jest jednym źródłem prawdy dla `canModerate` i dla doboru adresatów: rozjazd między nimi dałby moderatora bez powiadomień albo powiadomienia dla kogoś, kto nic nie może z nimi zrobić.
- **Listenery są `AFTER_COMMIT`**, jak reszta powiadomień i odwrotnie niż listenery kasowania konta (Claude.md §9). Powiadomienie o banie, który się nie zacommitował, to informacja o zdarzeniu, którego nie było.

## Konsekwencje

- **Zmiana jest addytywna dla API.** Cztery nowe wartości `NotificationType` w istniejącej odpowiedzi `/api/v1/notifications`; żaden endpoint nie zmienia kontraktu. Klient mobilny musi umieć wyświetlić nieznany typ zamiast się wywracać — to i tak było wymagane przy `COMMUNITY_MEMBER_JOINED`.
- **Powiadomienie nie niesie powodu bana ani treści zgłoszenia.** `Notification` ma płaski kształt (typ, aktor, zasób, czas) i rozszerzanie go o pole tekstowe zmieniałoby model dla wszystkich typów naraz. Zbanowany widzi, że dostał bana i od kogo — nie widzi wpisanego powodu. Gdy okaże się potrzebny, naturalnym miejscem jest endpoint społeczności, a nie skrzynka.
- **Nadanie i odebranie roli moderatora nie powiadamia.** To zmiana uprawnień, nie decyzja wobec kogoś; nowy moderator zobaczy kolejkę, gdy wejdzie do społeczności. Świadomie zostawione na później.
- **Wciąż nie ma pushy ani maili.** Powiadomienia żyją w skrzynce w aplikacji, więc moderator dowiaduje się o zgłoszeniu przy następnym wejściu — lepiej niż „gdy sam zajrzy do kolejki", ale to nadal nie jest alert. Kanał push to osobna decyzja (tokeny urządzeń, zgody, koszt), a kanał e-mail istnieje (`MailSender`, ADR 0008) i byłby tańszym pierwszym krokiem.
- **Wyjątek `MODERATION` jest miejscem, w które trzeba patrzeć przy każdym nowym typie.** Domyślną odpowiedzią na „czy nowy typ dopisać do tego zbioru" jest **nie**: zbiór opisuje decyzje, których adresat nie może odrzucić, a nie powiadomienia uznane za ważne. Rozlanie się go na zwykłą aktywność cicho unieważniłoby blokadę.
- **Zbanowany dostaje powiadomienie, ale nadal nie ma jak się odwołać.** Ścieżka odwoławcza (kontakt z właścicielem, zdjęcie bana z informacją zwrotną) to osobna robota; ADR 0011 zostawił też bany bezterminowe, więc wygaśnięcie bana jako powiadomienie nie ma dziś czego reprezentować.
