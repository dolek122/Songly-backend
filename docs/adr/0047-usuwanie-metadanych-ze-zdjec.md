# 47. Usuwanie metadanych ze zdjęć

Data: 2026-10-21
Status: zaakceptowane

## Kontekst

Avatary i zdjęcia w postach są publiczne (ADR 0010). Backend rozpoznawał format po magicznych bajtach i zapisywał plik do magazynu bez zmian. Zdjęcie prosto z aparatu niesie w EXIF-ie współrzędne GPS, model telefonu i czas wykonania co do sekundy, a często też miniaturę. Zdjęcie zrobione w domu podawało więc adres użytkownika każdemu, kto otworzył post. Miniatura w EXIF-ie to osobny, znany wyciek: po przycięciu zdjęcia potrafi nadal pokazywać całość.

Aplikacja na Androida przekodowuje zdjęcie przed wysyłką (obraca według EXIF-u i kompresuje), więc z niej metadane nie wychodzą. To jednak właściwość jednego klienta. iOS, web z §15 i każdy, kto woła API bezpośrednio, wysłałby plik z metadanymi.

## Decyzja

- Każdy wgrany obraz przechodzi przez `SanitizedImage.from` (`common.media`), zanim trafi do magazynu. Obie ścieżki wgrywania, avatar i zdjęcie posta, korzystają z niego zamiast z samego `ImageType.detect`.
- **Bez przekodowania.** Kontener jest przepisywany po segmentach (JPEG) albo blokach (PNG, WebP), a dane obrazu są kopiowane bajt w bajt.
- **Lista dozwolonych, nie zakazanych.** Zostaje to, co wpływa na wygląd: dane obrazu, przezroczystość, animacja, profil kolorów ICC, JFIF bez miniatury i znacznik Adobe. Giną EXIF, XMP, IPTC, komentarze, bloki tekstowe PNG, `tIME`, bloki nieznane, MPF i wszystko, co doklejono za końcem obrazu.
- **Orientacja przeżywa.** Z EXIF-u czytamy tylko znacznik orientacji i jeśli nie jest „normalna”, zapisujemy nowy, minimalny blok EXIF z tą jedną liczbą. Z oryginalnego bloku nie przechodzi żaden bajt.
- WebP: flagi XMP i EXIF w `VP8X` są poprawiane do bloków, które zostały, a rozmiar RIFF przeliczany.
- **Plik, którego struktury nie da się przeczytać, odpada** z tym samym `415 UNSUPPORTED_IMAGE` co plik nierozpoznany.

## Uzasadnienie

- **Dlaczego nie przekodowanie (ImageIO).** Obniża jakość JPEG-a przy każdym wgraniu. Java nie ma dekodera WebP. Wymaga też zdekompresowania obrazu do pamięci, a 5 MB PNG potrafi rozwinąć się do gigabajtów pikseli, czyli prostej bomby dekompresyjnej na serwerze. Przepisanie kontenera kosztuje tyle, co skopiowanie pliku.
- **Dlaczego lista dozwolonych.** Formaty mają dziesiątki typów bloków, a programy wymyślają własne. Lista zakazanych przepuszczałaby każdy nowy sposób zapisu metadanych, lista dozwolonych przepuszcza tylko to, co znamy.
- **Dlaczego orientacja zostaje.** Telefon zapisuje piksele tak, jak leżała matryca, i dopisuje „obróć o 90°”. Bez tego znacznika każdy portret z iPhone'a wyświetlałby się bokiem. Sama liczba od 1 do 8 nie mówi nic o osobie.
- **Dlaczego odrzucenie zamiast przepuszczenia.** Pliku, którego nie umiemy przeczytać, nie umiemy też oczyścić. Telefon i program graficzny zapisują poprawne pliki, więc odrzucenie dotyka wyłącznie plików uszkodzonych albo spreparowanych.

## Konsekwencje

- Kontrakt API się nie zmienia. Kod błędu jest ten sam, zmienia się tylko to, że uszkodzony plik nie przechodzi.
- Pliki wgrane przed tą zmianą leżą w magazynie z metadanymi. Klient Androida metadanych nie wysyła, więc dotyczy to tylko plików wgranych innymi drogami. Oczyszczenie ich wymagałoby osobnego zadania, które pobiera, oczyszcza i nadpisuje pliki pod tymi samymi kluczami.
- Testy wgrywania nie mogą już używać samych magicznych bajtów. `TestImages` daje prawdziwe, minimalne obrazy i metadane takie, jakie dopisuje aparat.
