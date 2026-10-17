# 35. Deduplikacja powiadomień o przywracanym stanie

Data: 2026-10-05
Status: zaakceptowane

## Kontekst

ADR 0034 ograniczył tempo zapisów per użytkownik i sam nazwał, czego nie zamknął: *„Pętla like/unlike jest teraz ograniczona, ale nie zamknięta. 40 zaczepek na 10 minut to nadal kilkaset powiadomień na dobę od jednej osoby. Dokładniejsze rozwiązanie leży w domenie"*.

Pętla nie dotyczy tylko lajków. Te same cztery ścieżki są idempotentne wyłącznie w obrębie jednego stanu, a cofnięcie i przywrócenie stanu za każdym razem publikuje nowe zdarzenie:

| Pętla | Zdarzenie | Zasób w powiadomieniu |
|---|---|---|
| `PUT`/`DELETE /posts/{id}/likes` | `POST_LIKED` | post |
| `PUT`/`DELETE /posts/{id}/shares` | `POST_SHARED` | post |
| `PUT`/`DELETE /communities/{id}/membership` | `COMMUNITY_MEMBER_JOINED` | społeczność |
| `POST /friends/requests` + `DELETE /friends/requests/{id}` | `FRIEND_REQUEST_RECEIVED` | zaproszenie (nowe przy każdym wysłaniu) |

Z punktu widzenia publikującego to są prawdziwe nowe zdarzenia: wiersz lajka powstał, poprzedni zniknął. Dla adresata drugi lajk tej samej osoby pod tym samym postem nie niesie żadnej informacji.

## Decyzja

Deduplikacja w `NotificationService.record`, czyli w module `notification`, dwiema regułami:

1. **`ONCE_PER_RESOURCE`** (`POST_LIKED`, `POST_SHARED`, `COMMUNITY_MEMBER_JOINED`): jedno powiadomienie na trójkę (adresat, aktor, zasób) **na zawsze**. Przeczytanie go niczego nie zmienia.
2. **`COLLAPSED_WHILE_UNREAD`** (`FRIEND_REQUEST_RECEIVED`): dopóki od tego aktora wisi nieprzeczytane zaproszenie, kolejne je **odświeża** (nowy `resourceId`, nowa data) zamiast dokładać drugie. Po przeczytaniu nowe zaproszenie daje nowe powiadomienie.

Komentarze i wszystkie typy moderacyjne zostają bez zmian.

## Uzasadnienie

- **Reguła w skrzynce, nie u publikującego.** Tylko skrzynka wie, co już pokazała. Content musiałby pamiętać „ten użytkownik kiedyś to polubił” po usunięciu wiersza lajka, czyli dostałby tabelę, która istnieje wyłącznie na potrzeby powiadomień — dane jednego modułu trzymane w drugim (§2). Zdarzenia zostają wierne faktom, więc analityka dalej liczy każde polubienie.
- **Dwie reguły, bo zaproszenie zachowuje się inaczej.** Każde wysłanie tworzy nowy wiersz z nowym identyfikatorem, więc „raz na zasób” nie złapałoby pętli wyślij/anuluj. „Raz na parę na zawsze” byłoby z kolei za mocne: były znajomy, który po roku zaprasza ponownie, to prawdziwa wiadomość. Przy lajku nie ma odpowiednika tej sytuacji, bo ponowny lajk tego samego posta nigdy nie jest nowiną.
- **Odświeżenie, a nie pominięcie, przy zaproszeniu.** Pominięcie zostawiłoby powiadomienie wskazujące anulowane zaproszenie, czyli klient po kliknięciu dostawałby 404 przy akceptacji. Po odświeżeniu powiadomienie zawsze wskazuje zaproszenie, które faktycznie czeka.
- **Przeczytanie nie otwiera furtki przy `ONCE_PER_RESOURCE`.** Gdyby otwierało, sprawca dostawałby jedno powiadomienie na każde zajrzenie ofiary do skrzynki, a ofiara uczyłaby się, że czytanie powiadomień je sprowadza. Przy zaproszeniach tę cenę płacimy świadomie, bo alternatywą jest gubienie prawdziwych zaproszeń.
- **Bez zmian w filtrze blokad.** Zdeduplikowane powiadomienie ukrywa się i wraca po odblokowaniu dokładnie jak dotąd (§9).

## Konsekwencje

- **Zwykły indeks, nie unikalny** (`idx_notifications_dedup` na `(user_id, actor_id, type)`). Sprawdzenie i zapis to dwa kroki, więc dwa równoległe zapisy mogą zostawić duplikat — tak jak w każdym przypadku przed tą zmianą. Unikalny indeks i tak nie pasowałby do drugiej reguły, gdzie unikalność zależy od `read_at`. Wyjątek z naruszenia ograniczenia w listenerze `AFTER_COMMIT` byłby też gorszy od rzadkiego duplikatu.
- **Data powiadomienia o lajku to data pierwszego lajka**, nie ostatniego. Po pętli lajk w skrzynce wygląda na stary. To zamierzone: przesuwanie go na górę byłoby nową wiadomością w innym kształcie.
- **Kasowanie powiadomień przez użytkownika** (gdyby kiedyś powstało) otworzy furtkę przy `ONCE_PER_RESOURCE`: po skasowaniu nic nie pamięta, że powiadomienie już było. Wtedy skasowanie powinno być ukryciem, nie `DELETE`.
- **Limit z ADR 0034 zostaje.** Deduplikacja chroni precyzyjnie cztery znane pętle, limit przekrojowo wszystko inne, łącznie z komentarzami, których deduplikować się nie da.
- Zgodnie z §11 sprawdzone, że testy potrafią spaść: po wyłączeniu obu reguł czerwienieją oba nowe testy w `NotificationFlowIntegrationTest`.
