# 19. Metryki i analityka moderacji

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

Moderacja urosła przez siedem ADR-ów (0011–0018) i **nie zostawiała po sobie ani jednego śladu w danych**: `AnalyticsEventType` kończył się na `COMMUNITY_MEMBER_JOINED`, a żadna z bramek dodanych w ADR 0018 nie miała licznika. To boli podwójnie, bo w tych samych ADR-ach zapisaliśmy kilka decyzji odłożonych **do czasu, aż dane pokażą**:

- ADR 0014: zmiana terminu aktywnego bana „do rewizji, gdyby moderatorzy realnie się o to potykali",
- ADR 0017: odpowiedź moderatora tekstem „do rozważenia, gdyby odwołania okazały się częste",
- ADR 0018: waga zgłoszenia zależna od historii zgłaszającego „ma sens dopiero przy danych o tym, ile zgłoszeń bywa trafnych", plus wprost zapisany brak metryki odrzuceń.

Zdanie „zrobimy to, gdy dane pokażą" jest puste, dopóki nic tych danych nie zbiera.

## Decyzja

Dwa odbiory, tak jak dotąd w tym projekcie — analityka produktowa w bazie i metryki operacyjne w Micrometerze. Bez nowych zdarzeń domenowych: listener modułu analytics konsumuje dokładnie te, które już istnieją.

**Analityka (ADR 0004, liczniki bez PII):** `POST_FIRST_REPORTED`, `POST_REPORT_DISMISSED`, `COMMUNITY_POST_REMOVED`, `COMMUNITY_BAN_ISSUED_PERMANENT`, `COMMUNITY_BAN_ISSUED_TEMPORARY`, `BAN_APPEAL_SUBMITTED`, `BAN_APPEAL_ACCEPTED`, `BAN_APPEAL_REJECTED`.

**Metryki (§13):** `songly.moderation.report.rejected{reason="rate-limit|banned"}` i `songly.moderation.mail.suppressed{kind="report|appeal"}`.

## Uzasadnienie

- **`POST_FIRST_REPORTED` liczy posty, nie zgłoszenia — i nazwa musi to mówić.** `PostReportedEvent` powstaje wyłącznie przy pierwszym zgłoszeniu danego posta, bo bramka anty-spamowa siedzi u publikującego (ADR 0012), a notification i mailer dziedziczą ją za darmo (ADR 0013). Przeniesienie bramki do konsumentów dałoby dokładny wolumen, ale zduplikowałoby warunek w dwóch miejscach i złamało zasadę, na której stoją dwa ADR-y. Wybraliśmy uczciwą nazwę zamiast wygodnej: licznik nazwany `POST_REPORTED` po cichu zaniżałby wolumen, a nikt czytający `POST_REPORTED: 12` nie domyśliłby się, że to dwanaście **postów**. Pełnej liczby zgłoszeń i tak nie trzeba liczyć zdarzeniami — leży w `post_reports`, która ma `created_at` (i którą od ADR 0018 i tak odpytujemy przy limicie).
- **Bany rozdzielone na bezterminowe i czasowe.** Analityka liczy same typy, bez wymiarów (ADR 0004), więc odpowiedź na „czy bany czasowe się przyjęły" wymaga dwóch serii. Ich suma to wszystkie nowe bany, więc nic nie tracimy.
- **Kluczowa nowa miara to `BAN_APPEAL_ACCEPTED` do `BAN_APPEAL_SUBMITTED`** — jak często moderacja sama uznaje, że się pomyliła. Tej liczby nie dało się dotąd wziąć znikąd, a jest to jedyny sygnał zwrotny o **jakości** decyzji moderacyjnych, nie tylko o ich liczbie.
- **Liczniki odrzuceń są odwrotnością tych z §13 pod względem oczekiwanej wartości: mają stać nisko.** Przyrost `rate-limit` to ktoś zasypujący kolejkę, przyrost `banned` to zbanowany dobijający się kanałem, który właśnie stracił. Dokładnie ten sam powód, dla którego liczymy odrzucenia rate limitera: trafienie w bramkę jest inaczej z zewnątrz niewidoczne.
- **`mail.suppressed` istnieje, bo wyciszenie wygląda identycznie jak cisza.** Moderator nie wie, że mail nie przyszedł — brak maila i brak spraw są dla niego nieodróżnialne. Stała, niezerowa seria oznacza, że odstęp z ADR 0018 jest za długi albo że trwa zasypywanie; bez licznika jedyną drogą do tej wiedzy byłby debug w logach.
- **Nie dublujemy tego, co już jest.** Liczba udanych zgłoszeń czy banów nie dostaje osobnej metryki Micrometera — `songly.domain.events{type}` liczy je przy okazji zapisu do analityki (§13: „`http.server.requests` pokrywa już latencję i statusy, więc ich nie dublujemy"). Micrometer dostaje tylko to, czego analityka z definicji nie widzi: **żądania odrzucone** i **maile, które nie poszły** — czyli zdarzenia, które nie są zdarzeniami domenowymi.
- **Kardynalność tagów jest ograniczona z góry** (dwie wartości `reason`, dwie `kind`) — żadnego identyfikatora społeczności ani użytkownika w tagach, ta sama zasada co przy `songly.rate.limit.rejected{path}`, gdzie tagujemy wzorzec, nie URI.
- **Zero PII, jak cała analityka** (ADR 0004): zapisujemy typ i czas, nie kto kogo zbanował. Do odpowiedzi na pytania z ADR 0014/0017/0018 to wystarcza, a moderacja z natury dotyczy danych szczególnie wrażliwych — im mniej ich w tabeli agregatów, tym lepiej.

## Konsekwencje

- **Osiem nowych wartości w `AnalyticsEventType`**, widocznych w odpowiedzi `/api/v1/analytics/summary` i w kontrakcie OpenAPI. Zmiana addytywna; endpoint jest za rolą admina (ADR 0006), więc dane moderacyjne nie wypływają dalej niż dotąd.
- **Zgłoszenie zamknięte usunięciem posta nie zostawia śladu jako rozstrzygnięcie.** Kasowanie posta zabiera kaskadą jego zgłoszenia, więc „ile zgłoszeń było trafnych" liczymy pośrednio: `POST_REPORT_DISMISSED` to odrzucenia, a `COMMUNITY_POST_REMOVED` to usunięcia, ale drugi licznik obejmuje też posty usunięte bez żadnego zgłoszenia. Dokładna trafność wymagałaby statusu `RESOLVED` na zgłoszeniu przed kasowaniem posta — do zrobienia razem z historią moderacji, jeśli okaże się potrzebna.
- **Metryki są procesowe, nie klastrowe.** Liczniki Micrometera resetuje restart i każda instancja ma własne; od tego jest Prometheus, który sumuje i pamięta. Analityka w bazie jest trwała i wspólna — dlatego to ona, a nie metryki, odpowiada na pytania produktowe.
- **Dopiero teraz zdania „do rewizji, gdy dane pokażą" z ADR 0014, 0017 i 0018 mają pokrycie.** Rewizję trzeba jednak świadomie wykonać — same serie niczego nie zmienią; wracamy do nich, gdy pojawi się ruch.
