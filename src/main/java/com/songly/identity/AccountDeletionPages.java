package com.songly.identity;

import org.springframework.web.util.HtmlUtils;

/**
 * Treść stron usuwania konta (ADR 0043). Zwykłe łańcuchy zamiast silnika
 * szablonów: stron jest cztery, a jedyną zmienną spoza naszego kodu jest token
 * z adresu — dla niego jedna zależność do escapowania nie uzasadnia dokładania
 * Thymeleafa do całej aplikacji.
 *
 * <p>
 * Treść odpowiada na pytania, które Google Play stawia stronie usuwania konta:
 * czyja to aplikacja, jak o usunięcie poprosić, co znika, a co i jak długo
 * zostaje. Lista „co usuwamy" musi iść za {@code *AccountDeletionListener}
 * wszystkich modułów — nowy moduł z danymi per-user dopisuje się i tam, i
 * tutaj.
 */
final class AccountDeletionPages {

	static final String START_PATH = "/account-deletion";
	static final String CONFIRM_PATH = "/account-deletion/confirm";

	private AccountDeletionPages() {
	}

	static String start(long ttlMinutes, String error) {
		String errorBlock = error == null
				? ""
				: "<p class=\"error\" role=\"alert\">" + HtmlUtils.htmlEscape(error) + "</p>";
		return layout("Usuwanie konta Songly", """
				<h1>Usuwanie konta Songly</h1>
				<p>Na tej stronie usuniesz swoje konto w aplikacji Songly razem ze wszystkimi
				danymi — także wtedy, gdy nie masz już aplikacji na telefonie.</p>

				<h2>W aplikacji</h2>
				<p>Zaloguj się i w zakładce <b>Profil</b> wybierz usunięcie konta. Aplikacja
				poprosi o hasło.</p>

				<h2>Bez aplikacji</h2>
				<ol>
				  <li>Wpisz adres e-mail, na który założono konto.</li>
				  <li>Otwórz link z wiadomości, którą od nas dostaniesz. Jest ważny przez %d min.</li>
				  <li>Na stronie z linku potwierdź usunięcie przyciskiem.</li>
				</ol>
				%s
				<form method="post" action="%s">
				  <label for="email">Adres e-mail konta</label>
				  <input id="email" name="email" type="email" autocomplete="email" required>
				  <button type="submit">Wyślij link</button>
				</form>
				<p class="note">Ze względów bezpieczeństwa nie mówimy tutaj, czy pod danym adresem
				jest konto. Wiadomość przyjdzie tylko wtedy, gdy jest.</p>

				<h2>Co usuwamy</h2>
				<p>Od razu i na stałe — bez okresu karencji i bez możliwości przywrócenia:</p>
				<ul>
				  <li>konto i dane logowania,</li>
				  <li>profil, zdjęcie profilowe i ulubione gatunki,</li>
				  <li>posty ze zdjęciami (razem z komentarzami innych osób pod nimi), komentarze,
				  polubienia i udostępnienia,</li>
				  <li>znajomych, zaproszenia i blokady,</li>
				  <li>członkostwa w społecznościach; społeczności, których jesteś właścicielem,
				  usuwamy w całości, razem z postami innych osób,</li>
				  <li>powiadomienia, ustawienia powiadomień i telefony zarejestrowane do powiadomień
				  push,</li>
				  <li>połączenie ze Spotify i status „teraz gram”,</li>
				  <li>złożone przez Ciebie zgłoszenia i historię decyzji moderacyjnych wobec Ciebie.</li>
				</ul>

				<h2>Co zostaje</h2>
				<ul>
				  <li>Anonimowe liczniki statystyczne (np. ile postów dodano danego dnia). Nie
				  zawierają niczego, co wskazuje na Ciebie.</li>
				  <li>Decyzje, które podjąłeś jako moderator społeczności (np. bany), zostają w
				  społeczności, ale bez wskazania Ciebie jako ich autora.</li>
				  <li>Logi techniczne serwera mogą przez krótki czas zawierać identyfikator konta
				  (nigdy adres e-mail). Usuwają się same po upływie okresu przechowywania logów.</li>
				</ul>
				""".formatted(ttlMinutes, errorBlock, START_PATH));
	}

	static String linkSent(long ttlMinutes) {
		return layout("Sprawdź skrzynkę — Songly", """
				<h1>Sprawdź skrzynkę</h1>
				<p>Jeśli pod podanym adresem jest konto Songly, wysłaliśmy na niego link do
				potwierdzenia usunięcia. Link jest ważny przez %d min.</p>
				<p>Nic nie przyszło? Zajrzyj do folderu ze spamem albo spróbuj ponownie za minutę.</p>
				<p><a href="%s">Wróć</a></p>
				""".formatted(ttlMinutes, START_PATH));
	}

	static String confirm(String token) {
		return layout("Potwierdź usunięcie konta — Songly", """
				<h1>Potwierdź usunięcie konta</h1>
				<p>Usunięcie jest <b>nieodwracalne</b>. Znikną profil, zdjęcia, posty, komentarze,
				znajomości i społeczności, których jesteś właścicielem.</p>
				<form method="post" action="%s">
				  <input type="hidden" name="token" value="%s">
				  <button type="submit" class="danger">Usuń konto na stałe</button>
				</form>
				<p class="note">Jeśli nie chcesz usuwać konta, po prostu zamknij tę stronę.</p>
				""".formatted(CONFIRM_PATH, HtmlUtils.htmlEscape(token)));
	}

	static String deleted() {
		return layout("Konto usunięte — Songly", """
				<h1>Konto usunięte</h1>
				<p>Twoje konto Songly i wszystkie dane opisane na stronie usuwania konta zostały
				usunięte.</p>
				<p>Jeśli aplikacja jest jeszcze na Twoim telefonie, wyloguje Cię przy najbliższym
				połączeniu z serwerem.</p>
				""");
	}

	static String invalidLink(long ttlMinutes) {
		return layout("Link nieważny — Songly", """
				<h1>Ten link jest nieważny</h1>
				<p>Mógł wygasnąć (jest ważny przez %d min), zostać już użyty albo zastąpiony
				nowszym linkiem.</p>
				<p><a href="%s">Zamów nowy link</a></p>
				""".formatted(ttlMinutes, START_PATH));
	}

	/**
	 * Bez zewnętrznych zasobów: strona nie ładuje niczego spoza tego dokumentu,
	 * więc token z adresu nie wycieka w nagłówku {@code Referer}, a nagłówek CSP z
	 * kontrolera może zabronić wszystkiego poza stylami inline.
	 */
	private static String layout(String title, String body) {
		return """
				<!doctype html>
				<html lang="pl">
				<head>
				<meta charset="utf-8">
				<meta name="viewport" content="width=device-width, initial-scale=1">
				<title>%s</title>
				<style>
				  :root { color-scheme: dark; }
				  body { margin: 0; background: #0b0f0c; color: #e8efe9;
				         font: 16px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
				  main { max-width: 640px; margin: 0 auto; padding: 32px 16px 48px; }
				  h1 { font-size: 1.6rem; margin: 0 0 16px; }
				  h2 { font-size: 1.15rem; margin: 32px 0 8px; color: #7ee2a0; }
				  a { color: #7ee2a0; }
				  label { display: block; margin: 16px 0 6px; font-weight: 600; }
				  input[type=email] { width: 100%%; box-sizing: border-box; padding: 12px; font: inherit;
				         border-radius: 8px; border: 1px solid #3a4a3f; background: #141b16; color: inherit; }
				  button { margin-top: 12px; min-height: 48px; padding: 0 20px; font: inherit; font-weight: 600;
				         border: 0; border-radius: 24px; background: #1db954; color: #04130a; cursor: pointer; }
				  button.danger { background: #e5484d; color: #fff; }
				  .note { color: #9fb0a4; font-size: 0.92rem; }
				  .error { color: #ff8f8f; font-weight: 600; }
				</style>
				</head>
				<body>
				<main>
				%s
				</main>
				</body>
				</html>
				""".formatted(HtmlUtils.htmlEscape(title), body);
	}
}
