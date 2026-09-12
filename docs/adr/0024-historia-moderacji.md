# 24. Historia moderacji

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

Cztery ADR-y zapisały w konsekwencjach ten sam brak, każdy ze swojej strony:

- **0014**: „wygasły ban znika z listy banów — moderator nie odróżni «nigdy nie był zbanowany» od «ban wygasł»; to wróci razem z historią moderacji",
- **0017** i **0023**: „brak historii odwołań — widać stan bieżącej sprawy, nie to, ile razy ktoś się odwoływał",
- **0019**: „zgłoszenie zamknięte usunięciem posta nie zostawia śladu jako rozstrzygnięcie… dokładna trafność wymagałaby pracy razem z historią moderacji",
- **0018** czekał na te dane, żeby móc ważyć zgłoszenia historią zgłaszającego.

Wspólny mianownik: system trzymał wyłącznie **stan** sankcji. Każda decyzja, która przestawała obowiązywać, przestawała też istnieć — a moderacja bez pamięci nie potrafi odpowiedzieć na najzwyklejsze pytanie przed wydaniem wyroku: „czy to pierwszy raz?".

## Decyzja

Nowy moduł domenowy `moderationlog` z tabelą `moderation_log_entries` — **append-only dziennik decyzji**, zasilany istniejącymi zdarzeniami domenowymi, z dwoma odczytami:

| Odczyt | Kto | Zakres |
|---|---|---|
| `GET /api/v1/communities/{id}/moderation-log` | moderacja społeczności | ta społeczność |
| `GET /api/v1/admin/moderation-log?userId=` | administracja | cała platforma, opcjonalnie jedna osoba |

Czternaście rodzajów wpisów: bany (nałożenie i zdjęcie), usunięcia postów, odrzucenia zgłoszeń, odwołania od banów i zawieszeń wraz z rozstrzygnięciami, zmiany roli moderatora, zawieszenia kont i ich zdjęcia.

## Uzasadnienie

- **Osobny moduł, nie rozszerzenie `analytics`.** Analytics jest z definicji zbiorem liczników **bez PII** (ADR 0004); dziennik identyfikujący ludzi złamałby tę regułę, a to ona czyni tamten moduł bezpiecznym. Nie pasuje też do `community` ani `identity`: decyzje pochodzą z trzech modułów naraz, a pytanie „co ten człowiek ma za sobą" przecina je wszystkie. Konsumowanie cudzych zdarzeń przez jeden moduł-odbiorcę to wzorzec, który w tym repozytorium działa już dwa razy (notification, analytics).
- **Dziennik jest append-only i nie ma ani jednej metody zmieniającej wpis.** Historia, którą da się poprawić, nie jest historią; skoro wpisy opisują decyzje już zapadłe, nie ma czego poprawiać. Bez zadania sprzątającego po czasie — decyzje moderacyjne podejmują ludzie, więc tabela rośnie w tempie ludzkim (inaczej niż `sessions`, które rosły z każdym odświeżeniem tokenu).
- **Bez powodu sankcji i bez treści odwołań — i to jest decyzja, nie oszczędność.** Powód żyje na wierszu kary, dopóki kara trwa. Żeby skopiować go do dziennika, trzeba by wnieść go do zdarzenia domenowego — a wtedy byłby jeden nieuważny krok od maila, czyli od dziury, którą ADR 0015 zamknął („tekst moderatora nie jedzie kanałem push"). Dziennik odpowiada więc na **„czy i ile razy"**, nie na „dlaczego". Ta granica jest celowo szczelniejsza niż wygodna.
- **`reportCount` zapisywany w chwili usunięcia posta** zamyka lukę z ADR 0019: kasowanie posta zabiera kaskadą jego zgłoszenia, więc po fakcie nie ma już czego zliczyć. Bez tej liczby nie da się odróżnić usunięcia zamykającego zgłoszenie od usunięcia z własnej inicjatywy — czyli policzyć, ile zgłoszeń bywa trafnych (czego z kolei czekał ADR 0018).
- **Nowe zdarzenie `CommunityBanLiftedEvent`** obok istniejącego „uznano odwołanie", nie zamiast: tamto mówi, *dlaczego* ban zniknął, i zasila powiadomienie, to mówi, *że* zniknął, i zasila dziennik. Bez niego historia pokazywałaby same nałożenia i „zbanowany trzy razy" nie dałoby się odróżnić od „zbanowany trzy razy i trzy razy ułaskawiony". Wygaśnięcie terminu wpisu nie tworzy — wygasanie jest leniwe (ADR 0014) i nie ma chwili, w której cokolwiek się dzieje.
- **Zawieszenia dostały w zdarzeniach `actorId`.** ADR 0022 świadomie nie dodał kolumny `suspended_by` (klucz obcy tabeli do samej siebie), ale dziennik ma `actor_id` z zerowaniem, więc to tutaj jest właściwe miejsce na „kto". Bez tego historia platformowa byłaby asymetryczna wobec społecznościowej — pokazywałaby sankcje bez autorów.
- **Paginacja tutaj jest na miejscu, w odróżnieniu od wykazów stanu** (ADR 0021, 0022, gdzie ją świadomie odrzuciłem): tamte listy są małe z natury i służą audytowi kompletności, a dziennik rośnie bez górnego ograniczenia.
- **Dwa odczyty, bo są dwie publiczności.** Moderator społeczności widzi wyłącznie swoją społeczność (sprawdzenie przez `CommunityMembership.canModerate` — tylko community wie, kto moderuje); administracja widzi wszystko, z gatingiem odziedziczonym po prefiksie `/api/v1/admin/**` (ADR 0021). Bez nazw użytkowników: moduł nie zależy od `profile`, a klient i tak je dołoży.
- **Kasowanie konta: wpisy *o* tej osobie znikają, wpisy *jej autorstwa* tracą autora.** Dokładnie ta asymetria co przy banach (ADR 0011): decyzja należy do społeczności i przeżywa odejście swojego autora, ale historia sankcji wobec nieistniejącego konta nie ma czego opisywać — a nowe konto to i tak nowa tożsamość, więc nic dla wykrywania recydywy nie tracimy.

## Konsekwencje

- **Historia społeczności ginie razem ze społecznością** (przez `CommunityDeletedEvent`, nie kaskadę w bazie — §8), więc także wtedy, gdy jej właściciel kasuje konto. Wpis bez społeczności nie miałby jak zostać odczytany, bo oba endpointy pytają albo o społeczność, albo o platformę; zostawiałby tylko rosnącą tabelę. Test utrwala oba przypadki.
- **Zmiany roli administratora nie trafiają do dziennika.** Są w śladzie audytowym i metryce (ADR 0021), a dziennik opisuje decyzje moderacyjne wobec użytkowników, nie nadawanie uprawnień. Gdyby administratorów było więcej, to pierwszy kandydat do dopisania.
- **ADR 0018 ma teraz swoje dane**, ale ważenie zgłoszeń historią zgłaszającego to nadal osobna decyzja — dziennik ją odblokował, nie wykonał.
- **Dwie pułapki, które złapały mnie przy pisaniu tego modułu**, obie warte zapisania, bo obie są ciche:
  - **Skrócone przeciążenie `record(...)` wołane na `this` omija proxy Springa**, a z nim `REQUIRES_NEW`. Zapis z listenera `AFTER_COMMIT` dołącza się wtedy do zakończonej transakcji i **przepada bez błędu** — w dzienniku brakowało połowy wpisów, a nic tego nie zgłaszało. Dlatego serwis ma jedną metodę zapisu z pełną listą argumentów i javadoc tłumaczący, czemu nie ma wygodniejszej.
  - **Nazwa klasy nasłuchującej nie mogła brzmieć `CommunityDeletedListener`**, bo taka jest już w module content, a Spring wyprowadza nazwę beana z prostej nazwy klasy. Objaw jest kompletnie niepowiązany z przyczyną: „ApplicationContext failure threshold exceeded" na losowym teście integracyjnym.
