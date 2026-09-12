# 25. Waga zgłoszeń zależna od historii zgłaszającego

Data: 2026-09-12
Status: zaakceptowane

## Kontekst

ADR 0018 zamknął zasypywanie kolejki od strony poczty i jednego konta, ale zostawił wprost nazwaną dziurę: „zasypanie samej kolejki przez wielu użytkowników pozostaje możliwe… realnym rozwiązaniem byłoby **zaufanie do zgłaszających (waga zgłoszenia zależna od historii)**, co ma sens dopiero przy danych o tym, ile zgłoszeń bywa trafnych". ADR 0024 te dane odblokował i powtórzył, że samo ważenie to nadal osobna decyzja.

Problem praktyczny: limit 20/h traktował tak samo człowieka, któremu moderacja odrzuciła dwadzieścia zgłoszeń, jak kogoś, kto zgłasza pierwszy raz. Moderator oglądający kolejkę też nie miał skąd wiedzieć, czy patrzy na zgłoszenie od kogoś wiarygodnego.

## Decyzja

Dwa niezależne zastosowania tej samej historii:

1. **Przydział zgłoszeń maleje z każdym odrzuceniem** — `limit - dismissalPenalty × liczba_odrzuceń`, nie niżej niż `minLimit`. Domyślnie 20, kara 5, podłoga 1: bez odrzuceń 20/h, po jednym 15, po dwóch 10, po trzech 5, dalej 1.
2. **Kolejka pokazuje moderatorowi liczbę odrzuconych zgłoszeń tej osoby** (`reporterDismissedCount`) — waga podana do oceny, nie odjęta za moderatora.

**Zasięgi są różne celowo**: limit liczy odrzucenia **globalnie**, kolejka pokazuje wyłącznie te **z tej społeczności**.

## Uzasadnienie

- **Różnica zasięgów jest tu najważniejsza i nie jest niekonsekwencją.** Limit musi być globalny, bo zasypywanie rozłożone na dziesięć społeczności wymyka się każdemu licznikowi liczonemu osobno — a przy tym nie ujawnia niczego nikomu: to liczba użyta wyłącznie do odpowiedzi „nie" wobec samego zgłaszającego. Podgląd w kolejce musi być lokalny, bo moderator społeczności nie ma prawa dowiedzieć się, co ten człowiek robił w społecznościach, z którymi nie ma nic wspólnego (Claude.md §8: moderacja jest społecznościowa, nie globalna). Ta sama liczba, dwa różne uprawnienia do jej poznania.
- **Kara liniowa, nie wykładnicza, i z podłogą nigdy równą zeru.** Odrzucenie jest oceną moderatora, nie dowodem złej woli; system, w którym kilka odrzuceń wycisza kogoś na stałe, pozwalałby wyłączyć narzędzie, którym da się zgłosić nadużycie samego moderatora. Jedno zgłoszenie na okno zostaje zawsze.
- **Moderator dostaje liczbę, nie ranking.** Kusiło, żeby sortować kolejkę po wiarygodności, ale to ukrywałoby zgłoszenia przed człowiekiem, który ma je rozpatrzyć — a przy dzisiejszym ruchu kolejka mieści się na ekranie. Liczba obok zgłoszenia daje ten sam kontekst bez chowania czegokolwiek.
- **Liczymy po `post_reports`, nie po historii moderacji (ADR 0024) — i to nie jest wybór, tylko granica modułów.** Dziennik żyje w module, który konsumuje zdarzenia modułu content; sięgnięcie po niego z content dałoby cykl, który łapie reguła 2 `ModuleBoundariesTest`. Koszt: odrzucenia, których post usunięto później, znikają razem z nim (kaskada), więc licznik bywa **zaniżony, nigdy zawyżony** — a to właściwa strona pomyłki, bo błąd działa na korzyść zgłaszającego.
- **Sygnał jest jednostronny i tak ma zostać na razie.** Liczymy odrzucenia, nie „trafienia": zgłoszenia zamknięte usunięciem posta znikają razem z nim, a `moderation_log_entries` wie tylko, ile ich było, nie czyje. Do pełnej trafności trzeba by logować jeden wpis na zgłaszającego przy usunięciu posta — osobna zmiana. Na dziś wystarcza, bo problemem z ADR 0018 było zasypywanie, a jego sygnałem są właśnie odrzucenia.
- **Odrzucone zgłoszenie nadal zajmuje okno** — i ta interakcja jest mniej oczywista, niż wygląda. Przydział liczy zgłoszenia z ostatniej godziny niezależnie od ich losu, więc ktoś, kto zgłosił cztery rzeczy i dostał cztery odmowy, jest zablokowany do końca okna, a podłoga ujawnia się dopiero w następnym. To jest spójne (podłoga znaczy „jedno na okno", nie „jedno zawsze pod ręką"), ale łatwo o tym zapomnieć przy czytaniu kodu — dlatego testy przesuwają okno jawnie, zamiast polegać na przypadku.

## Konsekwencje

- **Zmiana zachowania istniejącego endpointu**: `POST /posts/{id}/reports` może teraz zwrócić 429 wcześniej niż po dwudziestu zgłoszeniach. Kod błędu bez zmian (`REPORT_RATE_LIMIT_EXCEEDED`) — nie mówimy zgłaszającemu, że ma niższy przydział, bo to zaproszenie do zakładania kolejnego konta.
- **`PostReportResponse` ma nowe pole** — zmiana addytywna, kolejka moderacji dalej działa u klientów, które go nie znają.
- **Brak metryki „ile razy przydział został obniżony"**: `songly.moderation.report.rejected{reason="rate-limit"}` (ADR 0019) nie rozróżnia odmowy z limitu bazowego od tej z obniżonego. Rozdzielenie serii wymagałoby tagu o wartości zależnej od historii konkretnej osoby; jeśli okaże się potrzebne, właściwym tagiem jest `reduced=true/false`, nie liczba.
- **Reset historii nie istnieje.** Odrzucenia liczą się bez końca, więc po roku aktywności ktoś z kilkoma pomyłkami ma trwale niższy przydział. Świadomie na razie: okno czasowe na karę (np. „odrzucenia z ostatnich 90 dni") to jedna linia w zapytaniu i naturalna korekta, gdy pojawi się pierwszy taki przypadek — ale dopisywanie go teraz byłoby strojeniem parametrów bez ruchu.
