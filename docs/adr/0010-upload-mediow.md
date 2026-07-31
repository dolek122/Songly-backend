# 10. Upload mediów: pliki przez backend do magazynu S3-kompatybilnego

Data: 2026-07-31
Status: zaakceptowane

## Kontekst

`profiles.avatar_url` istniało od migracji `V2`, ale **nikt nie miał jak wgrać pliku**. Kolumna była zwykłym polem tekstowym w `PUT /api/v1/profiles/me`: użytkownik wpisywał dowolny adres, a my renderowaliśmy go w kliencie każdego, kto obejrzał ten profil. To nie było tylko niedokończone — to była dziura. Adres pod kontrolą obcej osoby, ładowany przez cudze aplikacje, to gotowy piksel śledzący IP oglądających, z możliwością podmiany treści w dowolnej chwili bez wgrywania czegokolwiek do nas.

Posty nie miały nawet tego. Pierwszy filar produktu (Claude.md §1, „content & social") był jedynym, którego backend w ogóle nie obsługiwał w warstwie mediów.

## Decyzja

Magazyn zgodny z S3 (Claude.md §4), jedna implementacja na wszystkie środowiska: MinIO lokalnie i w testach, Cloudflare R2 / AWS S3 na produkcji. Abstrakcja `ObjectStorage` w `common.storage` zna wyłącznie klucz, bajty i typ MIME.

**Pliki idą przez backend** (multipart), nie prosto do magazynu na podpisanym URL-u.

- `POST /api/v1/profiles/me/avatar` → zaktualizowany profil
- `DELETE /api/v1/profiles/me/avatar` → 204
- `POST /api/v1/posts/image` → `{key, url}`, potem `POST /api/v1/posts {content, imageKey}`

W bazie trzymamy **klucz**, nie URL (`profiles.avatar_key`, `posts.image_key`, migracja `V16`). `profiles.avatar_url` znika.

## Uzasadnienie

- **Przez backend, nie podpisanym URL-em — to jest główna decyzja tego ADR-u.** Podpisany URL jest tańszy i lepiej się skaluje, ale wyklucza jedyną rzecz, która tu naprawdę chroni: sprawdzenie, czym są przesłane bajty. Przy podpisanym URL-u klient wgrywa cokolwiek — plik wykonywalny, HTML, treść nielegalną — i dostaje na to publiczny adres w naszej domenie. Nagłówek `Content-Type` i nazwa pliku pochodzą od klienta, więc nie są dowodem na nic. Migracja na podpisane URL-e ma sens dopiero, gdy przepustowość backendu stanie się **zmierzonym** problemem (§10) — a wtedy będzie oznaczała świadome oddanie tej kontroli, nie jej przeoczenie.
- **Format rozpoznajemy po magicznych bajtach**, i z rozpoznanego typu bierzemy rozszerzenie klucza. Zaufanie nazwie pliku pozwoliłoby zapisać coś jako `.html` i zdobyć adres, spod którego serwujemy cudzy HTML na własnej domenie. Świadomie **nie** dekodujemy obrazu: to odsiewa pliki, które obrazem nie są nawet z pozoru, a nie próbuje być pełną walidacją — parsowanie cudzych danych w naszym procesie bywa samo w sobie wektorem ataku.
- **Limit rozmiaru egzekwuje kontener servletów**, nie nasz kod. Sprawdzenie w kontrolerze wymagałoby najpierw przyjęcia całego pliku, czyli działałoby dopiero po tym, jak szkoda została zrobiona. Mapowanie `MaxUploadSizeExceededException` na 413 jest nasze — bez niego za duży plik wracał jako 500 ze stack tracem i wyglądał w monitoringu jak awaria serwera.
- **Klucz w bazie, nie URL.** Adres publiczny zależy od domeny CDN-u, a ta się zmienia (migracja S3 ↔ R2, wpięcie własnej domeny). Z kluczem to jedna zmiana w konfiguracji; z zapisanymi URL-ami — `UPDATE` po każdej tabeli, która kiedykolwiek taki adres zapisała. Stąd też `public-base-url` jest **osobną** propercją od `endpoint`: przy R2 i CloudFroncie klienci czytają przez inną domenę, niż my wgrywamy.
- **`profiles.avatar_url` znika, a nie zostaje obok.** Dwa źródła prawdy dla jednego zdjęcia to pytanie „które wygrywa" zadawane w każdym miejscu odczytu. Kolumna leci razem z danymi — wartości pochodziły od użytkowników, wskazywały na cudze serwery i nie chcemy ich dalej serwować, a produkt nie jest wydany (§15).
- **Losowy człon w kluczu** (`avatars/{userId}/{uuid}.jpg`), zamiast nadpisywania w miejscu: pod starym adresem stoi cache CDN-u, więc nadpisanie pokazywałoby przez wiele godzin poprzednie zdjęcie i wyglądało jak zignorowana zmiana.
- **Klucz zdjęcia posta zawiera właściciela**, i to jest jedyne, co pozwala sprawdzić, że dołączane zdjęcie należy do autora. Bez tego wystarczyłby cudzy klucz w `imageKey`, żeby pokazać czyjeś zdjęcie pod swoim nazwiskiem — a przy skasowaniu takiego posta usunąć właścicielowi plik. Sprawdzeniem jest prefiks, a nie osobna tabela wgrań: klucz i tak musi być unikalny i i tak zawiera właściciela, więc dodatkowy wiersz przechowywałby informację, którą już mamy.

## Kasowanie plików: odwrotna faza niż kasowanie wierszy

To jest pułapka symetryczna do tej z §9 i warto ją mieć zapisaną obok tamtej.

Wiersze modułów muszą zniknąć **w tej samej transakcji**, przed usunięciem konta — trzyma je klucz obcy. Pliki muszą zniknąć **po commicie**, bo dopiero commit potwierdza, że nikt już ich nie wskazuje. Skasowanie pliku za wcześnie zostawia po wycofanej transakcji wiersz wskazujący na nieistniejący plik: awatar, który nigdy się nie wyświetli i którego nie da się odzyskać. Odwrotna pomyłka kosztuje osierocony plik — ułamek grosza, do posprzątania później.

Dlatego oba moduły publikują zdarzenie (`OrphanedAvatarEvent`, `OrphanedPostImageEvent`), a kasuje dopiero `@TransactionalEventListener(AFTER_COMMIT)`. Jedno miejsce obsługuje wszystkie powody osierocenia: podmianę, usunięcie, kasowanie posta, kasowanie społeczności i kasowanie konta.

Z tego samego powodu **wgranie** dzieje się przed commitem: wycofanie transakcji zostawia wtedy osierocony plik, a nie wiersz bez pliku. Zawsze wybieramy tę stronę pomyłki, która nie psuje danych.

## Konsekwencje

- **Breaking change:** `UpdateProfileRequest` traci pole `avatarUrl`, a `CreatePostRequest` zyskuje opcjonalne `imageKey`. `PostResponse` zyskuje `imageUrl`. Klient mobilny (`Songly-mobile`) wymaga zmiany: ekran edycji profilu przestaje wysyłać `avatarUrl`, dochodzi wybór pliku i dwa nowe wywołania.
- **Nowa zależność infrastrukturalna.** Aplikacja nie wystartuje sensownie bez skonfigurowanego magazynu. Lokalnie załatwia to `docker-compose` (MinIO + inicjalizacja bucketu z publicznym odczytem); na produkcji trzeba ustawić `STORAGE_*`. Nie ma implementacji „na dysk lokalny" jako alternatywy — S3-kompatybilny magazyn stawia się kontenerem w kilka sekund, więc druga ścieżka kodu byłaby tylko ścieżką, której nikt nie uruchamia na produkcji. (Inaczej niż przy poczcie w ADR 0008, gdzie fallback `log` istnieje, bo serwera SMTP nie postawi się mimochodem.)
- **Testy używają prawdziwego MinIO** (Testcontainers), z tego samego powodu, dla którego baza to Postgres, a nie H2 (§11). Zwrócony URL jest w testach pobierany **zwykłym, nieuwierzytelnionym klientem HTTP** — atrapa magazynu potwierdzałaby wyłącznie samą siebie, a pytanie brzmi „czy przeglądarka to pobierze".
- Bucket jest **publiczny do odczytu**. Avatary i zdjęcia w postach są z założenia publiczne — klient pobiera je wprost, bez naszego pośrednictwa. Gdyby kiedyś pojawiły się media prywatne (np. w społecznościach zamkniętych), będą potrzebowały osobnego bucketu i podpisanych URL-i do odczytu; to osobna decyzja.
- Świadomie **poza zakresem**: sprzątanie zdjęć wgranych, ale nigdy niedołączonych do posta. Użytkownik może wgrać plik i nie opublikować go — plik zostaje. Nie ma dziś licznika, który powiedziałby, czy to realny problem; gdy się pojawi, rozwiązaniem jest zadanie kasujące pliki z prefiksu `posts/` starsze niż X i niewskazywane przez żaden wiersz. Ta sama uwaga dotyczy limitu liczby wgrań na użytkownika: rate limiting jest per IP i nie obejmuje endpointów za JWT (§9).
- Świadomie poza zakresem także: skalowanie obrazów i miniatury. Klient dostaje oryginał. Przy avatarach na listach to zmarnowany transfer i pierwszy kandydat do poprawki, gdy zacznie boleć — ale wymaga albo przetwarzania obrazów u nas, albo CDN-u z transformacjami, czyli osobnej decyzji.
