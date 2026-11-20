# 50. Dokończenie połączenia ze Spotify w aplikacji

Data: 2026-10-28
Status: zaakceptowane

## Kontekst

Łączenie ze Spotify zaczyna się w aplikacji: `POST /api/v1/now-playing/spotify/connect` zapisuje w Redisie state wskazujący na pytające konto i zwraca adres autoryzacji. Przeglądarka przechodzi przez Spotify i wraca na publiczny callback. Callback zużywał state, wymieniał kod na tokeny i przypinał link do konta, które wskazał state (ADR 0038).

Nic nie wiązało powrotu z osobą, która zaczęła łączenie. Każdy mógł więc:

1. wywołać `/connect` na własnym koncie,
2. podesłać ofierze otrzymany adres autoryzacji, na przykład przez stronę, która za każdym razem pobiera świeży, bo state żyje 10 minut,
3. czekać, aż ofiara go otworzy.

Kto raz zgodził się na Songly, przy kolejnym wejściu zwykle nie widzi już ekranu zgody Spotify (bez `show_dialog` Spotify może od razu przekierować). Kliknięcie od razu odsyłało więc przeglądarkę ofiary z **jej** kodem na nasz callback. Callback przypinał jej Spotify do konta autora linku, a ten przez `GET /now-playing/me` czytał na bieżąco, czego ofiara słucha, bez końca. ADR 0038 zakładał, że callback osiąga się tylko z przepływu rozpoczętego w aplikacji, i właśnie to założenie nie było niczym egzekwowane.

## Decyzja

Łączenie ma dwa kroki, a konto przypina dopiero drugi.

- **Callback nie łączy konta.** Zużywa state, odkłada kod w Redisie pod nowym, losowym, jednorazowym **biletem** (`now-playing:oauth-ticket:`, `oauth-ticket-ttl`, domyślnie 5 min) i odsyła przeglądarkę na `songly://now-playing/spotify?result=authorized&ticket=…`.
- **`POST /api/v1/now-playing/spotify/connect/complete {ticket}`** (z JWT) zużywa bilet i porównuje konto, które zaczęło łączenie, z kontem pytającym. Dopiero przy zgodności wymienia kod na tokeny i zapisuje link. Odpowiedź to `204`, a przy błędzie `400 INVALID_CONNECT_TICKET`, `400 INVALID_AUTHORIZATION_CODE` albo `502 MUSIC_PROVIDER_ERROR`.
- **Jeden kod na wszystkie przyczyny złego biletu** (nieznany, zużyty, wygasły albo cudzy). Właściwy użytkownik w każdym z tych przypadków robi to samo: łączy jeszcze raz. Cudzy bilet zostawia wpis `WARN` z obydwoma `userId`, bo to ślad próby przejęcia.
- Wynik `result=connected` znika. Zostają `denied` i `error` z ADR 0038, ale callback zgłasza już tylko `INVALID_OAUTH_STATE` i `MUSIC_PROVIDER_ERROR`. Błędy wymiany kodu wracają z `/complete` jako zwykła odpowiedź API.

## Uzasadnienie

- **State nie wystarcza do dokończenia, bo zna go autor linku.** Sam pomysł „callback odkłada kod pod state'em, aplikacja kończy z tym samym state'em” nie zamykał niczego: autor linku zakończyłby łączenie własnym JWT i state'em, który sam dostał. Bilet powstaje dopiero w callbacku i trafia tylko do przeglądarki, która przeszła przez Spotify. W ataku jest to przeglądarka ofiary, a dalej jej aplikacja zalogowana na jej konto, więc porównanie kont odrzuca próbę.
- **Wymiana kodu przy dokończeniu, nie w callbacku.** Wymiana w callbacku wymagałaby trzymania tokenów, w tym długożyjącego refresh tokenu, w Redisie do czasu potwierdzenia. Kod autoryzacyjny bez naszego client secretu jest bezużyteczny i żyje krótko, więc może leżeć w Redisie bez szyfrowania.
- **Bilet ginie przy pierwszej próbie, także nieudanej.** Zła próba kończy sprawę: bilet, który mógł gdzieś wyciec, przestaje działać.
- **Bez `show_dialog=true`.** Wymuszony ekran zgody był najtańszą łatką, ale nie zamykał dziury. Ofiara widzi wtedy „Songly prosi o dostęp”, a skoro sama używa Songly, kliknie „Zgadzam się”. Każdemu ponownemu łączeniu dokładałby za to jeden ekran. Bilet zamyka dziurę bez tego kosztu.

## Konsekwencje

- **Zmiana kontraktu z aplikacją.** Klient musi obsłużyć `result=authorized` i wywołać `/complete`. Starsza wersja aplikacji, która zna tylko `connected`, nie dokończy łączenia. Na tym etapie nie ma wydanych wersji, więc nie trzymamy ścieżki zgodności wstecz.
- **Znany koszt, ta sama rodzina co ADR 0037:** schemat `songly://` nie jest zastrzeżony. Złośliwa aplikacja na telefonie ofiary mogłaby przechwycić bilet z podesłanego linku i oddać go autorowi, a ten dokończyłby łączenie na swoim koncie. Wymaga to cudzej aplikacji zainstalowanej u ofiary, a nie tylko kliknięcia. Przechwycenie biletu z własnego łączenia użytkownika szkodzi mniej: cudze konto dostaje odmowę, a użytkownik tylko łączy jeszcze raz. App Links zamkną to razem z linkami z maili.
- **Łączenia nie da się już dokończyć poza telefonem.** Adres autoryzacji otwarty na komputerze zostawiał wcześniej konto połączone, mimo że przekierowanie na `songly://` się nie udawało. Teraz bez aplikacji nie ma czym zużyć biletu. Klient i tak otwiera adres na urządzeniu.
- ADR 0038 przestaje być aktualny w części o wyniku `connected` i o tym, że przekierowanie nie niesie sekretu.
