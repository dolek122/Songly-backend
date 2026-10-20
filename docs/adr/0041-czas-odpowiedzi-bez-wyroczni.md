# 41. Czas odpowiedzi nie zdradza istnienia konta

Data: 2026-10-20
Status: zaakceptowane

## Kontekst

Logowanie, rejestracja i reset hasła odpowiadają tak samo niezależnie od tego, czy pod adresem jest konto (Claude.md §9, §13, ADR 0008, ADR 0009). Rejestracja broni się przy tym także czasem: hashuje hasło w obu gałęziach, bo „równość statusów bez równości kosztu jest ochroną pozorną”.

Dwie pozostałe ścieżki tej zasady nie trzymały:

- **Logowanie** przy nieznanym koncie kończyło się po jednym zapytaniu, a przy błędnym haśle dopiero po bcrypcie. Różnica to kilkadziesiąt milisekund na każdej próbie.
- **Reset hasła** wysyłał maila tylko wtedy, gdy konto istnieje, a wysyłka SMTP działa w wątku żądania (`AFTER_COMMIT` odpala się synchronicznie przed zwróceniem odpowiedzi). 202 dla istniejącego konta przychodziło więc o czas rozmowy z serwerem pocztowym później, czyli setki milisekund.

Przy okazji wyszło, że JavaMail ma domyślnie nieskończone timeouty. Zawieszony serwer pocztowy trzymał wątek żądania bez końca.

## Decyzja

1. `AuthService.login` przy nieznanym koncie porównuje hasło ze skrótem wyliczonym raz przy starcie tym samym enkoderem. Obie porażki kosztują jeden bcrypt.
2. Przy `songly.mail.provider=smtp` wysyłka idzie przez `AsyncMailSender`: dwa wątki, kolejka na 500 wiadomości, kontekst MDC przenoszony do wątku wysyłki. Przepełniona kolejka rzuca `MailDeliveryException`, który wołający już łapią. Zamknięcie aplikacji czeka do 30 s na przyjęte wiadomości.
3. Timeouty SMTP: 5 s na połączenie, 10 s na odczyt i zapis.

Kanał `log` zostaje synchroniczny.

## Uzasadnienie

- **Wysyłka poza wątkiem żądania usuwa różnicę u źródła.** Alternatywą było sztuczne opóźnianie odpowiedzi do stałego czasu, ale wtedy płaci każdy użytkownik, a stała musiałaby być dłuższa od najwolniejszej wysyłki.
- **Kolejka z granicą, nie blokowanie.** `CallerRunsPolicy` przy przepełnieniu przywróciłaby obie wady naraz: wyrocznię czasową i wątek żądania zakładnikiem serwera pocztowego.
- **Synchroniczny `log`.** Tam nie ma czego mierzyć, a testy czytają wiadomość zaraz po odpowiedzi. Testy podstawiają zresztą własny `MailSender`, więc dekorator ma osobny test jednostkowy.

## Konsekwencje

- Błąd wysyłki SMTP nie trafia już do `try/catch` w mailerach domenowych, tylko do logu `AsyncMailSender`. Wpis nie ma `userId` z mailera, ale ma `requestId` (i `userId`, jeśli żądanie było uwierzytelnione) z MDC.
- Restart w trakcie wysyłki może zgubić wiadomości z kolejki. Każda z nich da się zamówić ponownie (reset, rejestracja), a powiadomienie moderacyjne i tak leży w skrzynce w aplikacji.
- Logowanie na nieistniejące konto kosztuje teraz tyle co nieudane logowanie. Przed brute-force'em i tak chroni rate limiter.
