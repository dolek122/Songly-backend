# 15. Powód bana widoczny dla zbanowanego

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

Powód bana istnieje od ADR 0011, ale czytali go wyłącznie moderatorzy (lista banów). Do zbanowanego docierał sam fakt: powiadomienie w skrzynce (ADR 0012, płaski model — typ, aktor, zasób, czas), mail z nazwą społeczności i terminem (ADR 0013/0014) oraz błąd `BANNED_FROM_COMMUNITY` przy próbie pisania. Decyzji pozbawionej uzasadnienia nie da się ani zrozumieć, ani zakwestionować — a oba te ADR-y odsyłały widoczność powodu i terminu „do endpointu społeczności", który nie istniał.

## Decyzja

`GET /api/v1/communities/{id}/membership` — własny status pytającego wobec społeczności:

| status | pola znaczące |
|---|---|
| `MEMBER` | `role`, `joinedAt` |
| `BANNED` | `ban.reason`, `ban.createdAt`, `ban.expiresAt` |
| `NONE` | — |

Ban wygasły czyta się jak `NONE`. Powód **nie** trafia do maila ani do powiadomienia — wyłącznie tutaj.

## Uzasadnienie

- **Powód idzie do kanału pull, nie push — i to jest decyzja bezpieczeństwa, nie wygody.** Mail o banie omija blokady (ADR 0013) i dochodzi bez udziału odbiorcy. Gdyby niósł powód, cykl ban/unban stawałby się doręczaniem dowolnego tekstu moderatora prosto do cudzej skrzynki pocztowej — naszym transakcyjnym kanałem, którego ofiara nie może ani zablokować, ani wyłączyć. Endpoint tego wektora nie ma: po treść trzeba samemu przyjść, a obraźliwy powód obraża dopiero tego, kto go sam odpytał. Ta sama asymetria co między skrzynką (filtrowaną przy odczycie) a mailem (niecofalnym) w ADR 0013.
- **Endpoint statusu, nie rozszerzanie błędu `BANNED_FROM_COMMUNITY`.** Błędy mają spójny płaski format (kod, wiadomość, timestamp, §6) i rozszerzanie jednego wyjątku o pola domenowe rozjeżdżałoby kontrakt `@ControllerAdvice` dla wszystkich. Poza tym błąd przychodzi dopiero przy próbie pisania — klient potrzebuje statusu <i>przed</i> renderowaniem UI (przycisk „dołącz" vs. plansza „jesteś zbanowany"), czyli zapytania, nie wyjątku.
- **Bez tożsamości moderatora.** Ban należy do społeczności, nie do osoby, która go nałożyła (ADR 0011) — zbanowanemu prezentujemy decyzję społeczności. Powód służy zrozumieniu; wskazanie palcem konkretnego moderatora służyłoby głównie odwetowi. (W skrzynce aktor i tak jest — to świadoma niespójność: powiadomienie mówi „kto", status mówi „dlaczego i do kiedy".)
- **Członkostwo sprawdzane przed banem.** Po wygaśnięciu bana i powrocie oba wiersze istnieją naraz (wygasły ban zostaje w tabeli, ADR 0014) — stan opisuje członkostwo. Wygasły ban jako `NONE` jest spójne z „wygasły ban nie liczy się nigdzie": pokazywanie `BANNED` myliłoby klienta, któremu dołączenie właśnie zaczęło przechodzić.
- **Powód przestaje być prywatną notatką moderacji — to zmiana znaczenia istniejących danych.** Dotychczasowe powody pisano z założeniem, że czytają je tylko moderatorzy; mogą zawierać treści w stylu „podejrzenie multikonta X". Świadomie akceptujemy to na obecnej skali; javadoc `BanMemberRequest` mówi od teraz wprost, że powód widzi zbanowany, a wewnętrzne uwagi będą potrzebowały osobnego pola, gdy ktoś o nie poprosi.

## Konsekwencje

- **ADR 0014 częściowo zdezaktualizowany w konsekwencjach**: „zbanowany w aplikacji nie widzi terminu" przestało obowiązywać — widzi go (i powód) w statusie członkostwa. Błąd `BANNED_FROM_COMMUNITY` pozostaje bez zmian.
- **Ścieżka odwoławcza nadal nie istnieje**, ale przestała być zablokowana na braku informacji: zbanowany zna powód i termin, więc „skontaktuj się z moderacją" ma się do czego odnosić. Sam kanał kontaktu to osobna robota.
- **Klient mobilny może teraz rozróżnić stany przed renderowaniem**: „dołącz" (`NONE`), „jesteś członkiem" (`MEMBER`), „jesteś zbanowany, oto dlaczego i do kiedy" (`BANNED`) — bez prowokowania błędów zapisu.
- **`GET` obok `PUT`/`DELETE` na tej samej ścieżce `/membership`** — REST-owo naturalne domknięcie zasobu, zero nowych pojęć w API.
