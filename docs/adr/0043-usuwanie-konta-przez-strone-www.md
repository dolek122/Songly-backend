# 43. Usuwanie konta przez stronę WWW

Data: 2026-10-21
Status: zaakceptowane

## Kontekst

Google Play wymaga od aplikacji, w których zakłada się konto, dwóch dróg do jego usunięcia: w aplikacji i przez **stronę WWW, na której można o to poprosić bez instalowania aplikacji**. Adres strony wpisuje się w formularzu „Bezpieczeństwo danych”. Strona ma pokazywać nazwę aplikacji, kroki, co jest usuwane, a co i jak długo zostaje.

Mieliśmy tylko pierwszą drogę: `DELETE /api/v1/auth/me` z hasłem (Claude.md §9). Nie ma domeny ani aplikacji webowej (§15), a jedynym publicznym adresem jest backend na Renderze.

## Decyzja

Backend serwuje cztery proste strony HTML pod `/account-deletion`:

1. `GET /account-deletion` — kroki w aplikacji i bez niej, formularz z adresem e-mail, lista tego, co usuwamy i co zostaje.
2. `POST /account-deletion` — zawsze ta sama strona „sprawdź skrzynkę”. Mail z linkiem idzie tylko wtedy, gdy konto istnieje.
3. `GET /account-deletion/confirm?token=…` — strona z przyciskiem. **Samo otwarcie linku niczego nie usuwa.**
4. `POST /account-deletion/confirm` — usuwa konto tym samym `AccountEraser`, którego używa `DELETE /auth/me`.

Token działa jak przy resecie hasła (ADR 0008): w bazie leży sam skrót, na konto przypada jeden żywy token, obowiązuje odstęp między wysyłkami, TTL wynosi 1 h, a wysyłka idzie `AFTER_COMMIT`.

## Uzasadnienie

- **Mail, nie hasło w formularzu.** Kto nie pamięta hasła, musiałby najpierw je zresetować, a link resetu otwiera aplikację (`songly://`, ADR 0037), której ta osoba nie ma. Dostęp do skrzynki to ten sam dowód, którym reset hasła i tak oddaje całe konto, więc poziom ochrony się nie obniża.
- **Przycisk zamiast kasowania z linku.** Skanery poczty i podglądy linków otwierają adresy z wiadomości bez udziału człowieka. GET, który kasuje konto, usuwałby konta, których właściciel nawet nie widział.
- **Link w mailu z konfiguracji (`ACCOUNT_DELETION_CONFIRM_URL`), nie z nagłówka `Host`.** Podrobiony `Host` kierowałby link z tokenem kasującym konto ofiary na serwer napastnika.
- **Bez ochrony CSRF.** Podrobiony formularz prośby może tylko wysłać link na wskazany adres, a to napastnik zrobi i sam. Podrobione potwierdzenie wymaga tokenu, którego napastnik nie zna.
- **Restrykcyjne nagłówki zamiast sesji.** `Content-Security-Policy` zabrania wszystkiego poza stylami inline i wysyłaniem formularzy na własny adres. `Referrer-Policy: no-referrer` nie wypuszcza tokenu z adresu. `X-Frame-Options: DENY` (domyślne w Spring Security) blokuje osadzanie strony w cudzej ramce.
- **HTML składany w kodzie, nie w Thymeleafie.** Stron są cztery, a jedyną zmienną spoza naszego kodu jest token, który przechodzi przez `HtmlUtils.htmlEscape`. Silnik szablonów dla tylu stron byłby zależnością na zapas.
- **Poza kontraktem OpenAPI** (`@Hidden`), bo to nie jest operacja dla klienta mobilnego.

## Konsekwencje

- `AccountEraser` jest jedynym miejscem, które kasuje konto. Nowa tabela modułu `identity` z kluczem obcym do `users` dopisuje się tylko tam.
- Lista „co usuwamy” na stronie musi iść za listenerami kasowania konta wszystkich modułów. Nowy moduł z danymi per-user dopisuje się w obu miejscach.
- Zawieszenie konta (ADR 0022) nie blokuje tej drogi, tak samo jak `DELETE /auth/me`, bo sankcja nie może odbierać prawa z RODO.
- Strona żyje pod adresem backendu. Na darmowym planie Rendera pierwsze wejście po uśpieniu trwa 30–60 s, co dotyczy także recenzenta Google. Po przejściu na własną domenę trzeba zmienić `ACCOUNT_DELETION_CONFIRM_URL` i wpis w Google Play.
- Przed wysłaniem formularza do Google trzeba uzupełnić to, czego backend nie wie: nazwę dewelopera z karty sklepu i okres przechowywania logów oraz kopii zapasowych u dostawcy hostingu.
