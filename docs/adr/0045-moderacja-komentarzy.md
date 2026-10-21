# 45. Moderacja komentarzy i anonimowy aktor decyzji

Data: 2026-10-21
Status: zaakceptowane

## Kontekst

Moderacja społeczności (ADR 0011) obejmowała posty, ale nie komentarze. Komentarz trolla pod cudzym postem mógł usunąć tylko jego autor. Moderator miał do wyboru zostawić komentarz albo skasować cały post, czyli ukarać jego autora za cudzą treść.

Przy okazji wyszło, że skrzynka powiadomień wydaje przy decyzjach moderacyjnych nazwę i awatar moderatora. Push i mail podpisywały się społecznością (ADR 0040, 0013), a ADR 0015 mówi wprost, że decyzja należy do społeczności, nie do osoby, bo wskazanie palcem służy odwetowi. W skrzynce tę zasadę egzekwował tylko klient mobilny, przez to, że nie wyświetlał nazwy przy znanych mu typach. Nowy typ, którego klient jeszcze nie zna, trafia do ogólnego tekstu „$actor ma dla Ciebie powiadomienie”, czyli pokazałby moderatora z imienia.

## Decyzja

- `DELETE /api/v1/posts/{postId}/comments/{commentId}` dopuszcza oprócz autora **właściciela i moderatorów społeczności**, w której jest post. Pod zwykłymi postami nic się nie zmienia: tam chroni wyłącznie autorstwo (§8), także przed autorem posta.
- Usunięcie cudzego komentarza publikuje `CommunityCommentRemovedEvent` i działa jak usunięcie posta: powiadomienie `COMMUNITY_COMMENT_REMOVED` z zasobem = post (należy do `MODERATION`, więc przechodzi przez blokady i wychodzi mailem), wpis `COMMUNITY_COMMENT_REMOVED` w dzienniku moderacji i licznik w analityce.
- Przy typach z `NotificationType.DECIDED_BY_MODERATION` (decyzje, w których aktorem jest moderator albo administrator rozstrzygający w sprawie adresata) skrzynka zwraca `actorDisplayName` i `actorAvatarUrl` jako `null`. Sprawdzane per wiersz, bo ten sam moderator może mieć na tej samej stronie zwykłe powiadomienie z nazwą.

## Uzasadnienie

- **Ten sam endpoint, nie nowy.** Usunięcie komentarza to jedna operacja z dwoma źródłami uprawnień, tak samo jak przy postach. Kontrakt się nie zmienia, a klient potrzebuje tylko przycisku przy cudzych komentarzach w społeczności, w której ma rolę moderatora.
- **Zasobem jest post**, bo w przeciwieństwie do usuniętego posta istnieje i to do niego powiadomienie może prowadzić.
- **Anonimizacja na serwerze, nie w kliencie.** Zasada, której pilnuje tylko klient, pęka przy każdym nowym typie i przy każdym nowym kliencie (web z §15).

## Konsekwencje

- `actorId` zostaje w odpowiedzi, bo kontrakt z mobilką ma je jako wymagane. Zdeterminowany klient wciąż może sprawdzić profil po identyfikatorze. Pełne ukrycie wymagałoby zmiany kontraktu i zostaje na później.
- Kontrakt OpenAPI zyskał wartości enumów `NotificationType`, `ModerationAction` i `AnalyticsEventType`. Kopię w Songly-mobile trzeba odświeżyć. Do czasu aktualizacji klient pokaże „Ktoś ma dla Ciebie powiadomienie”, bez tekstu „Moderacja usunęła Twój komentarz” i bez przejścia do posta.
- Komentarzy nadal nie da się zgłaszać. Moderator usuwa to, co sam zobaczy, a kolejka zgłoszeń dotyczy postów.
