# 51. Poprawki wdrożeniowe po drugim przeglądzie backendu

Data: 2026-11-19
Status: zaakceptowane

## Kontekst

Drugi przegląd całego backendu uruchamiał aplikację tak, jak działa na Renderze:
w kontenerze 512 MB, z prawdziwym SMTP, za proxy i pod obciążeniem. Testy integracyjne tego nie robią. Działają na MockMvc, bez hosta poczty, z nagrywającymi kanałami i bez limitu pamięci, więc siedem znalezisk nie miało szans się w nich pojawić:

1. **Instancja z prawdziwym SMTP nie wstawała.** Boot rejestruje swój `JavaMailSender` pod nazwą `mailSender`, gdy tylko jest `spring.mail.host`. Nasz bean miał tę samą nazwę, a nadpisywanie definicji jest wyłączone, więc start kończył się `BeanDefinitionOverrideException`. Działo się tak nawet przy `provider=log`, wystarczył sam host.
2. **Pamięć ponad limit.** Bez flag JVM w kontenerze 512 MB / 1 CPU wybiera Serial GC i 128 MB sterty (25%). Sterta była zajęta w 94%, a resztę zjadały metaspace (~145 MB), kod JIT, stosy wątków i bufory. RSS wynosił 418 MB po starcie i 526 MB po dwóch minutach obciążenia (`docker stats`: 503,8 / 512 MiB).
3. **Wyczerpanie puli połączeń.** Dwa mechanizmy, oba przez listenery `AFTER_COMMIT`, które działają, zanim żądanie odda swoje połączenie:
   - Push wychodził synchronicznie z listenera `AFTER_COMMIT` zapisu do skrzynki, czyli przy dwóch trzymanych połączeniach, przez cały czas rozmowy z FCM (do 7 s przy timeoutach).
   - Niezależnie od pushy, listenery `AFTER_COMMIT` w czterech modułach (skrzynka, analityka, dziennik moderacji, profil po rejestracji) otwierają `REQUIRES_NEW`, czyli wątek trzyma jedno połączenie i czeka na drugie. Gdy wszystkie połączenia trzymają takie wątki, nikt nie dostaje drugiego. Domyślnie było 200 wątków Tomcata na 10 połączeń. Pod obciążeniem 40 klientów na prawdziwym Tomcacie: 8 żądań/s, p95 30 s, 29 odpowiedzi 500 i 150 przekroczeń czasu oczekiwania na połączenie w logu.
4. **Health check zależał od Redisa i poczty.** Render sprawdzał `/actuator/health`, które liczy oba, a oba są fail-open. Awaria Redisa zdejmowała więc z ruchu instancję, która działała. Przy SMTP każde sprawdzenie łączyło się dodatkowo z serwerem poczty.
5. **`?sort=` od klienta trafiał do każdego zapytania.** `?sort=foo` kończył się 500 na każdej liście, a `?sort=bio` sortował po dowolnym polu encji, także niezaindeksowanym i niewystawionym w odpowiedzi. Przy okazji wyszło, że **sufit `max-page-size=100` z §6 nigdy nie działał**: `@EnableSpringDataWebSupport` (potrzebne do `PagedModel`) wyłączało autokonfigurację Boota, a razem z nią wszystkie `spring.data.web.pageable.*`. Każda lista oddawała do 2000 wierszy na żądanie. Wyszukiwarka profili i lista gatunków nie miały przy tym `ORDER BY`, więc kolejne strony mogły się nakładać.
6. **Limit per IP dało się obejść**, a konto nie miało własnego. `FORWARD_HEADERS_STRATEGY=framework` ufał nagłówkowi `Forwarded` i lewemu wpisowi `X-Forwarded-For`, a oba wpisuje klient. Każde żądanie ze zmyślonym adresem dostawało więc świeże okno limitu (sprawdzone na prawdziwym Tomcacie). Nawet przy szczelnym limicie per IP atak rozproszony na jedno hasło nie miał żadnej granicy.
7. **Polskie znaki w 401/403/429 wychodziły jako `?`.** Te odpowiedzi piszą filtry, poza Spring MVC, a Tomcat koduje writer w ISO-8859-1, gdy kodowanie nie jest ustawione.

## Decyzja

### 1. Nazwa beana poczty

Bean nazywa się `songlyMailSender`. `MailConfigTest` uruchamia `MailConfig` razem z `MailSenderAutoConfiguration` Boota przy `provider=smtp` i ustawionym hoście. Testy integracyjne tej kombinacji nie uruchamiają.

### 2. Flagi JVM w obrazie

`Dockerfile` ustawia `JDK_JAVA_OPTIONS` i `MALLOC_ARENA_MAX=2`:

- `MaxRAMPercentage=40` (~205 MB sterty),
- `TieredStopAtLevel=1` (sam C1),
- `ReservedCodeCacheSize=48m`,
- `MaxMetaspaceSize=192m`,
- `Xss512k`,
- `MaxDirectMemorySize=32m`,
- `ExitOnOutOfMemoryError`.

Pomiar w `eclipse-temurin:21-jre` z `--memory=512m --cpus=1`:

| Konfiguracja | RSS po starcie | RSS pod obciążeniem | Przepustowość (8 klientów) |
|---|---|---|---|
| bez flag | 418 MB | 526 MB | 241 żądań/s |
| flagi z C2 | 417 MB | 492 MB | 248 żądań/s |
| flagi, sam C1 | 373 MB | 402 MB | 186 żądań/s |

Przy 16 klientach przez 6 minut RSS stał na 410 MB.

### 3. Push w tle i rozmiar puli względem wątków

- **Push wychodzi z ograniczonej puli** (`BoundedExecutor`, 2 wątki, kolejka 1000). Listener `AFTER_COMMIT` tylko zleca wysyłkę. Odczyty (urządzenia, blokady, zawieszenia, nazwy) i rozmowa z FCM odbywają się w wątku puli, bez połączeń żądania. Przepełniona kolejka gubi push z `songly.push.deliveries{outcome="dropped"}`, nigdy żądanie. Ta sama klasa obsługuje teraz `AsyncMailSender` (ADR 0041). Kopiowanie MDC, wątki demony i zamykanie z 30 s na dokończenie są w jednym miejscu.
- **Pula połączeń jest większa niż liczba wątków, które mogą czekać na drugie połączenie.** Takich wątków jest tyle, ile wątków Tomcata i schedulera. Ustawiamy `server.tomcat.threads.max=20` i `hikari.maximum-pool-size=30`, przy 6 wątkach schedulera. `minimum-idle=5`, żeby w spoczynku baza nie trzymała 30 procesów. Warunek pilnuje `ConnectionPoolSizingTest`: czyta `application.properties` i czerwienieje, gdy pula nie przekracza sumy wątków albo gdy któraś wartość nie jest ustawiona jawnie. Domyślne Springa łamią ten warunek, więc brak wpisu też jest błędem.

### 4. Health check

Grupa `readiness` liczy `readinessState` i `db`, a `render.yaml` sprawdza `/actuator/health/readiness`. Pełne `/actuator/health` zostaje do diagnostyki.

### 5. Kolejność list należy do serwera

- `sort` od klienta jest ignorowany. `WebPaginationConfig` owija resolver Spring Data i zostawia z `Pageable` wyłącznie stronę i rozmiar, więc `@PageableDefault` i sufit rozmiaru działają dalej.
- Zapytanie stronicowane musi mieć własne `ORDER BY`. Wyszukiwarka sortuje po `lower(displayName), userId`, a gatunki po nazwie.
- `PagedModel` włącza propercja `spring.data.web.pageable.serialization-mode=via-dto`, nie adnotacja. Dzięki temu `max-page-size=100` naprawdę obowiązuje.
- Kontrakt OpenAPI nie opisuje już `sort`.

### 6. Adres klienta i limit prób logowania

- **`FORWARD_HEADERS_STRATEGY=native`** na Renderze. `RemoteIpValve` Tomcata ignoruje `Forwarded`, czyta `X-Forwarded-For` od prawej i bierze pierwszy adres spoza sieci wewnętrznych, czyli ten, który dopisało proxy Rendera.
- **Limit prób logowania na adres** (`songly.auth.login.account-limit=10` na `account-window=15m`). `LoginAttemptLimiter` liczy każdą próbę przed sprawdzeniem hasła (`INCR` w Redisie), a udane logowanie zeruje licznik. Ponad limit: 429 `LOGIN_RATE_LIMIT_EXCEEDED` z `Retry-After`, także przy poprawnym haśle, i `songly.auth.login.attempts{outcome="throttled"}`. Kluczem jest skrót adresu, bez sprawdzania, czy konto istnieje. Fail-open jak reszta limitów.
- `ApiException` może nieść `Retry-After` (`getRetryAfterSeconds`), więc 429 z wyjątku domenowego mówi klientowi to samo co 429 z filtra.

### 7. Kodowanie odpowiedzi z filtrów

`TooManyRequestsResponse`, `RestAuthenticationEntryPoint` i `RestAccessDeniedHandler` ustawiają UTF-8 jawnie.

## Uzasadnienie

- **Sam C1, nie C2.** C2 zostawiał 20 MB zapasu do limitu, a każdy dodatkowy wątek, większa strona czy fala kompilacji mogły go zjeść. Zabicie kontenera przez OOM to restart i zerwanie wszystkich strumieni SSE. Na 0,1 CPU darmowego planu procesor kończy się przed przepustowością C1. Spadek o ~23% widać dopiero na pełnym rdzeniu.
- **Rozmiar puli, nie asynchroniczne listenery.** `REQUIRES_NEW` z `AFTER_COMMIT` to wzorzec, który zaleca sam Spring. Zakleszczenie wynika z proporcji puli do wątków, nie z wzorca. Przeniesienie listenerów do puli w tle zmieniłoby semantykę w czterech modułach: profil nie istniałby jeszcze zaraz po weryfikacji adresu, a skrzynka nie pokazywałaby powiadomienia zaraz po akcji. Zwolnienie połączenia przy commicie (`DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION`) gryzie się z resetowaniem flagi read-only połączenia przez Springa po transakcji. Z kolei 20 wątków Tomcata to dużo przy 0,1 CPU. Pod tym samym obciążeniem nowa konfiguracja dała 181 żądań/s bez błędów, a stara 8 żądań/s.
- **Push w tle mimo poprawionej puli.** Niezmiennik usuwa zakleszczenie, ale nie czekanie. Wątek trzymający dwa połączenia przez kilka sekund rozmowy z FCM dalej blokował innych.
- **Ignorować, nie odrzucać.** 400 dla nieznanego pola wymagałoby rozpoznania trzech różnych wyjątków: zapytań pochodnych, JPQL i natywnych. Ostatni wygląda tak samo jak prawdziwy błąd w SQL-u. Kontrakt nigdy nie obiecywał sortowania (§6), a nieznane parametry ignorujemy wszędzie. Sortowanie klienta i tak nie odwracało list z własnym `ORDER BY`, bo Spring Data dokłada je jako drugi klucz. Rządziło tylko tam, gdzie zapytanie kolejności nie miało, i właśnie tam ustawiamy ją jawnie.
- **`native` jest bezpieczne także przy złym założeniu o topologii.** Gdyby proxy Rendera dopisywało za adresem klienta jeszcze jeden publiczny adres, valve wzięłoby ten adres. Limit liczyłby wtedy za grubo, wspólnym kubełkiem, ale nie dałoby się go obejść.
- **Liczymy próby, nie porażki.** Liczenie samych porażek wymaga odczytu przed bcryptem i zapisu po nim, a równoległe próby przechodziłyby w szczelinie między nimi. Próba ponad limit nie kosztuje przy tym bcrypta, więc limit chroni też procesor.

Każdy nowy test został sprawdzony mutacją (§11). Dwie mutacje poprawiły same testy:

- Test „sortowanie klienta nie odwróci feedu” przechodził bez poprawki, bo Spring Data i tak nie odwracał list z `ORDER BY`. Usunęliśmy go, zamiast zostawić test, który niczego nie dowodzi.
- Test pushy z dwiema sekundami oczekiwania na połączenie nie odróżniał wysyłki synchronicznej od asynchronicznej. Ma teraz sekundę.

## Konsekwencje

- **Na Renderze RemoteIpValve zakłada, że proxy łączy się z adresu prywatnego i dopisuje adres klienta na końcu `X-Forwarded-For`.** Tego nie dało się sprawdzić z sandboksa. Po wdrożeniu trzeba potwierdzić, że limit per IP nie odcina wszystkich naraz.
- **Push może przyjść o ułamek sekundy później**, a przy przepełnionej kolejce przepada. Skrzynka zapisuje powiadomienie tak jak wcześniej, więc ginie tylko dzwonek, nie informacja.
- **Testy integracyjne wysyłają pushe synchronicznie** (`songly.notification.push.async-delivery=false` w `AbstractIntegrationTest`). Ścieżkę asynchroniczną pokrywa wyłącznie `PushDeliveryConcurrencyIntegrationTest`.
- **Podnosząc `server.tomcat.threads.max` albo `spring.task.scheduling.pool.size`, trzeba podnieść pulę**, inaczej test nie przejdzie. Darmowy Postgres Rendera ma limit połączeń, którego nie dało się sprawdzić z sandboksa. 30 mieści się w typowym limicie 100. Wolne wgrania zdjęć zajmują wątek Tomcata na czas przesyłania, więc 20 wątków to też granica równoległych wgrań.
- **Na większym planie flagi JVM trzeba przejrzeć.** Najpierw do zdjęcia jest `TieredStopAtLevel=1`. `JDK_JAVA_OPTIONS` ustawione w panelu Rendera nadpisuje wartość z obrazu.
- **Obcy może zablokować logowanie na cudzy adres** na 15 minut, wysyłając 10 złych haseł. Zalogowane urządzenia działają dalej, bo odświeżanie tokenu nie jest limitowane. To cena ochrony przed atakiem rozproszonym.
- **Klient dostaje najwyżej 100 wierszy na stronę**, nawet gdy prosi o więcej. Aplikacja mobilna nigdy nie prosi o więcej niż 100. Lista gatunków przychodzi teraz alfabetycznie.
- **`?sort=` jest ignorowany bez błędu**, jak każdy nieznany parametr.
