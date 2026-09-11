# 16. Powiadomienia o zmianie roli moderatora

Data: 2026-09-11
Status: zaakceptowane

## Kontekst

ADR 0012 świadomie pominął zmiany ról: „nadanie i odebranie roli moderatora nie powiadamia — to zmiana uprawnień, nie decyzja wobec kogoś; nowy moderator zobaczy kolejkę, gdy wejdzie do społeczności". Od tamtej pory kontekst się zmienił dwukrotnie. Po pierwsze, rola moderatora niesie dziś obowiązki z własnym strumieniem zdarzeń: powiadomienia i **maile** o zgłoszeniach (ADR 0013) zaczynają przychodzić z chwilą nadania roli — bez informacji „zostałeś moderatorem" pierwszy taki mail jest zagadką. Po drugie, odebranie roli objawiało się wyłącznie 403-kami na kolejce zgłoszeń — czyli decyzja wyglądała jak awaria, dokładnie ten problem, od którego zaczął się ADR 0012.

## Decyzja

Dwa nowe typy powiadomień z jednego zdarzenia `CommunityMemberRoleChangedEvent` (community): `COMMUNITY_MODERATOR_GRANTED` do nowego moderatora, `COMMUNITY_MODERATOR_REVOKED` do byłego. Zdarzenie leci **tylko przy faktycznej zmianie** roli. Oba typy są **zwykłymi powiadomieniami**: nie wchodzą do zbioru `MODERATION`, podlegają filtrowi blokad i nie wychodzą mailem.

## Uzasadnienie

- **Poza zbiorem `MODERATION` — i to jest najważniejsza decyzja tego ADR-u.** ADR 0012 zapisał w konsekwencjach, że domyślną odpowiedzią na „czy nowy typ dopisać do `MODERATION`" jest **nie**, a zbiór opisuje decyzje, których adresat nie może odrzucić — nie „powiadomienia uznane za ważne". Ta granica dostaje tu doprecyzowanie: chodzi o decyzje **ograniczające** adresata (ban, usunięcie treści, rozstrzygnięcie zgłoszenia). Zmiana roli nadaje albo odbiera uprawnienia, ale niczego nie ogranicza w zwykłym uczestnictwie — po degradacji człowiek dalej czyta i pisze jak każdy członek. Kto zablokował właściciela, temu wolno nie widzieć od niego także tego; koszt ponosi wyłącznie on sam. To pierwszy typ moderacyjno-podobny, który świadomie zostaje po zwykłej stronie granicy — precedens na przyszłość.
- **Konsekwencja: brak maila.** Granica kanału e-mail pokrywa się ze zbiorem `MODERATION` (ADR 0013) i ta zmiana jej nie rusza. Nowy moderator i tak musi wejść do aplikacji, żeby moderować; a o zgłoszeniach maile dostanie niezależnie.
- **Tylko faktyczna zmiana publikuje zdarzenie** — ta sama zasada co „tylko nowy ban" (ADR 0014): `setMemberRole` z rolą, którą członek już ma, jest idempotentnym brakiem zmiany, więc powtórzone kliknięcie właściciela nie dokłada powiadomień. Przy okazji przestaje wykonywać pusty `UPDATE`.
- **Jedno zdarzenie z kierunkiem jako boolean (`moderationGranted`), nie z rolą.** Pierwsza wersja niosła `CommunityRole` i listener notification porównywał ją ze stałą `CommunityRole.MODERATOR` — **`ModuleBoundariesTest` natychmiast to ściął** (reguła 1: do innego modułu wyłącznie przez interfejsy i zdarzenia; enum ról to wewnętrzny słownik community). To zresztą pierwszy raz, gdy ta reguła spadła na prawdziwym kodzie, a nie na próbnym naruszeniu z §11. Przy jedynych osiągalnych przejściach MEMBER↔MODERATOR (`OWNER` jest w `setMemberRole` nieosiągalny — ADR 0011) boolean niesie pełną informację, a pułapka znika też dla przyszłych konsumentów zdarzenia. Mapowanie na typ powiadomienia siedzi w listenerze modułu notification, tam gdzie reszta takich mapowań.
- **Ban moderatora nie generuje zdarzenia o roli.** Właściciel banując moderatora odbiera mu i członkostwo, i rolę — ale powiadomienie o banie (ADR 0012) już to niesie; drugie powiadomienie „straciłeś rolę" byłoby szumem o tej samej decyzji.

## Konsekwencje

- **Zmiana addytywna dla API**: dwie nowe wartości `NotificationType`; klient mobilny wyświetla nieznane typy od `COMMUNITY_MEMBER_JOINED` (ADR 0012).
- **Domknięta ostatnia pozycja z listy „świadomie zostawione na później" ADR 0012** — po tej zmianie każda decyzja właściciela lub moderatora wobec konkretnej osoby zostawia ślad w jej skrzynce.
- **Zdegradowany moderator może nie zauważyć degradacji**, jeśli zablokował właściciela — świadomy koszt pozostania poza `MODERATION`. Górna granica szkody: spróbuje wejść w kolejkę i dostanie 403, czyli stan sprzed tego ADR-u.
