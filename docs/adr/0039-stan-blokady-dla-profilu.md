# 39. Stan blokady dla ekranu cudzego profilu

Data: 2026-10-18
Status: zaakceptowane

## Kontekst

Mobilka dostała blokowanie z ekranu cudzego profilu. Backend nie miał jednak zapytania „czy blokuję tę osobę”: była tylko stronicowana lista `GET /api/v1/blocks`. Profil osoby zablokowanej wcześniej pokazywał więc „Zablokuj” zamiast „Odblokuj”, a odblokować dało się wyłącznie z ekranu znajomych.

Naturalne miejsce na tę informację, czyli pole w `GET /api/v1/profiles/{id}`, jest zamknięte: `profile` nie może zależeć od `social`, bo `social` zależy już od `ProfileDirectory`. Powstałby cykl, który łapie reguła 2 `ModuleBoundariesTest`. To ta sama granica, przez którą wyszukiwarka nie filtruje blokad (Claude.md §9).

## Decyzja

Nowy `GET /api/v1/blocks/{userId}` w module `social` zwraca `{"blocked": true|false}`. Odpowiada wyłącznie na pytanie, czy **pytający** blokuje danego użytkownika. Klient woła go obok pobrania profilu.

## Uzasadnienie

- **Granica modułów zostaje nietknięta.** Stan blokady to dane `social` i stamtąd jest wydawany. Alternatywa, czyli przeniesienie blokad pod `ProfileDirectory` albo odwrócenie zależności, przestawiałaby graf modułów dla jednego pola na jednym ekranie.
- **Tylko jeden kierunek i to jest warunek prywatności, nie skrót.** Pytanie „czy on blokuje mnie” zdradzałoby cudzą decyzję, którą reszta API celowo ukrywa: treści blokującego dają zablokowanemu 404, nie 403 (Claude.md §9). Zablokowany pytający o blokującego dostaje `false`, czyli prawdę o własnej decyzji.
- **Bez sprawdzania istnienia użytkownika.** Dla nieistniejącego id `false` jest prawdą i nie mówi niczego, czego nie powie `GET /profiles/{id}`.
- **Bez paginacji, wbrew §6,** bo to jeden wiersz, nie lista.

## Konsekwencje

- Ekran profilu robi dwa żądania zamiast jednego. To akceptowalny koszt za brak cyklu między modułami.
- Endpoint jest odczytem, więc nie podlega limitowi zapisów z ADR 0034.
