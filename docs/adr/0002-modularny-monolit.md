# 2. Modularny monolit zamiast mikroserwisów

Data: 2026-07-19
Status: zaakceptowane

## Kontekst

Songly to produkt planowany na lata rozwoju, z ośmioma docelowymi modułami domenowymi (identity, profile, social, content, now-playing, matching, community, notification). Trzeba zdecydować, czy dzielić je na osobne serwisy od startu, czy trzymać razem.

## Decyzja

Jeden deployowalny artefakt (Spring Boot JAR), wewnętrznie podzielony na moduły domenowe (package-by-feature: `com.songly.identity`, `com.songly.content`, itd.), komunikujące się przez jasno zdefiniowane interfejsy Javy / zdarzenia Springa — nigdy przez bezpośredni dostęp do encji innego modułu.

## Uzasadnienie

- Mikroserwisy na tym etapie to pułapka: koszt operacyjny (deployment, observability, sieć, spójność danych między serwisami) pojawia się zanim jest ruch, który by go uzasadniał.
- Modularny monolit z jasnymi granicami daje 90% korzyści izolacji domenowej (łatwość zmiany, testowalność, brak "boskich" klas) bez kosztu rozproszonego systemu.
- Każdy moduł ma własne repozytoria, własną logikę biznesową i własne DTO na granicy — żaden moduł nie zwraca na zewnątrz swoich encji JPA.
- Granice modułów wyznaczone teraz to furtka do wydzielenia pojedynczego modułu w osobny serwis później (np. `now-playing`, gdyby dostał nieproporcjonalnie duży ruch) bez przepisywania całości.

## Konsekwencje

- Wydzielenie serwisu następuje dopiero przy konkretnym, zmierzonym powodzie (skalowanie, osobny zespół, inny cykl release'ów) — nie "na zapas".
- Wymaga dyscypliny code review: pilnować, żeby moduły nie zaczęły grzebać bezpośrednio w encjach/repozytoriach innych modułów, bo to unieważnia całą korzyść tej architektury.
