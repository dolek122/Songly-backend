# 48. Rejestr wgranych zdjęć postów

Data: 2026-10-21
Status: zaakceptowane

## Kontekst

Zdjęcie do posta wgrywa się osobnym żądaniem `POST /api/v1/posts/image`, zanim post powstanie (ADR 0010). Aplikacja na Androida robi to od razu po wybraniu zdjęcia z galerii. Plik trafiał do magazynu bez żadnego wiersza w bazie, a przy publikacji backend sprawdzał tylko kształt klucza (`posts/{autor}/{uuid}.{rozszerzenie}`).

Wynikały z tego trzy problemy:

- **Porzucone zdjęcia zostawały na zawsze.** Wybrane i niewysłane, wgrane przed zamknięciem aplikacji, wgrane przez skrypt. Nic ich nie liczyło i nic nie sprzątało.
- **Brak limitu.** Jedynym ograniczeniem był limit zapisów per użytkownik (60 na minutę, ADR 0034). Przy 5 MB na plik to ponad 400 GB na dobę z jednego konta, płatne przez nas w magazynie.
- **Ten sam klucz dawało się przypiąć do dwóch postów.** Usunięcie jednego kasowało plik, a drugi post zostawał ze zdjęciem, którego nie da się wyświetlić.

## Decyzja

- Nowa tabela `post_image_uploads (image_key, owner_id, created_at)` w module `content`. Wiersz powstaje przy wgraniu i **ginie przy publikacji posta**. Klucz przechodzi wtedy do `posts.image_key`.
- **Przypięcie zużywa wiersz** (`DELETE ... WHERE image_key = ? AND owner_id = ?`). Zero usuniętych wierszy daje `400 UNKNOWN_POST_IMAGE`, ten sam kod co dotąd. Rejestr zastępuje sprawdzanie kształtu klucza.
- **Najwyżej 10 oczekujących na osobę** (`songly.content.post-image.max-pending`). Kolejne wgranie usuwa najstarsze oczekujące, a plik ginie po commicie (`OrphanedPostImageEvent`).
- **`AbandonedPostImageCleanupJob` co godzinę kasuje wgrania starsze niż 24 h** (`pending-ttl`). Najpierw wiersz we własnej transakcji, potem plik, a błąd magazynu przerywa przebieg.
- Kasowanie konta usuwa wiersze w tej samej transakcji (FK do `users` bez kaskady, §9), a pliki po commicie.

## Uzasadnienie

- **Wypieranie zamiast odmowy.** Mobilka wgrywa przy wyborze, więc kilka porzuconych zdjęć to zwykłe przeglądanie galerii. Odmowa po przekroczeniu limitu blokowałaby człowieka na dobę, a wypieranie ogranicza magazyn tak samo (10 × 5 MB na konto) i nikogo nie blokuje. Wypierane jest najstarsze, czyli to, które najpewniej porzucono.
- **Liczba usuniętych wierszy rozstrzyga wyścigi.** Przypięcie, wypieranie i sprzątanie usuwają ten sam wiersz. Postgres przepuszcza jedno z nich, a pozostałe dostają 0. Plik kasuje tylko ten, kto usunął wiersz, a przypina tylko ten, kto go zużył. Dzięki temu post nigdy nie wskazuje skasowanego pliku.
- **Najpierw wiersz, potem plik**, tak jak przy kasowaniu wierszy i plików w §9. Gdy magazyn zawiedzie po usunięciu wiersza, zostaje osierocony plik, a nie post bez zdjęcia. Przerwanie przebiegu po pierwszym błędzie sprawia, że przy awarii magazynu osierocony jest jeden plik, a nie wszystkie.
- **Rejestr zamiast kształtu klucza.** Przechodzi wyłącznie klucz, który sami wydaliśmy temu użytkownikowi. Kształt nie chronił przed podwójnym przypięciem, a rejestr chroni przed wszystkim, przed czym chronił kształt.

## Konsekwencje

- Pliki wgrane przed tą zmianą i nigdy nieprzypięte zostają w magazynie, bo nie mają wierszy. Migracja SQL nie widzi magazynu. Jednorazowe sprzątanie wymagałoby wylistowania prefiksu `posts/` i porównania go z `posts.image_key`.
- Wgranie zrobione tuż przed wdrożeniem i przypinane po nim dostanie `400 UNKNOWN_POST_IMAGE`. Klient pokazuje błąd, a użytkownik wybiera zdjęcie jeszcze raz.
- **Nieaktualne do ADR 0049:** `S3ObjectStorage.delete` połykał błąd magazynu i zostawiał po nim tylko wpis w logu, więc przebieg sprzątania wcale się nie przerywał. Przy awarii magazynu kasował wszystkie zaległe wiersze i osierocał wszystkie pliki. Od ADR 0049 kasowanie rzuca `ObjectStorageException` i przerwanie działa tak, jak opisuje ta decyzja.
- Post nie może dziś zmienić zdjęcia. Gdy pojawi się edycja posta, podmiana zdjęcia musi zużywać nowy wiersz i oddawać stary plik do skasowania.
