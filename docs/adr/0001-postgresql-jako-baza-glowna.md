# 1. PostgreSQL jako baza główna

Data: 2026-07-19
Status: zaakceptowane

## Kontekst

Songly potrzebuje bazy dla danych o silnej strukturze relacyjnej: użytkownicy, posty, znajomości, komentarze, gatunki. Dane muszą być spójne (np. relacje friend/friendship, unikalność e-maili) i zapytywalne złożonymi joinami (feed, dopasowywanie po gatunkach).

## Decyzja

PostgreSQL jako jedyna baza relacyjna projektu.

## Uzasadnienie

- Silna spójność (ACID) i dojrzałe wsparcie transakcji — kluczowe dla danych typu znajomości, konta, płatności w przyszłości.
- Wsparcie dla kolumn JSON(B), gdyby część danych (np. metadane utworu z zewnętrznego API) wymagała elastyczności bez oddzielnej bazy dokumentowej.
- Dojrzały ekosystem w Spring Data JPA, dobre wsparcie w narzędziach do migracji (Flyway) i w Testcontainers.
- Mongo/NoSQL odrzucone: dane Songly są z natury relacyjne (user-post-comment-like, friend graph), wymuszanie tego w dokumentowej bazie oznaczałoby ręczne pilnowanie spójności w kodzie aplikacji.

## Konsekwencje

- Migracje schematu przez Flyway (`src/main/resources/db/migration`), `spring.jpa.hibernate.ddl-auto=validate` — schemat zawsze jawny w repo, nigdy generowany automatycznie na produkcji.
- Dane ulotne o wysokiej częstotliwości zapisu (np. "co kto teraz słucha") świadomie NIE trafiają do Postgresa — trzymane w Redis (patrz sekcja 4 Claude.md).
