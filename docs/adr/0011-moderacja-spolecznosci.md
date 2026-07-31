# 11. Moderacja społecznościowa: moderatorzy, bany i kolejka zgłoszeń

Data: 2026-07-31
Status: zaakceptowane

## Kontekst

Claude.md §8 dał właścicielowi społeczności prawo kasowania dowolnego posta u siebie i nazwał to „minimum moderacji, które uzasadnia istnienie tego modułu". W praktyce to minimum nie zamykało żadnej pętli:

- **Nie było jak niczego zgłosić.** Właściciel dowiadywał się o problematycznej treści tylko wtedy, gdy sam ją zobaczył. Przy społeczności większej niż kilkanaście osób to znaczy: nie dowiadywał się.
- **Nie było jak nikogo powstrzymać.** Usunięcie posta nie przeszkadzało autorowi napisać go ponownie, a dołączenie do społeczności jest otwarte i natychmiastowe (§8) — więc nawet usunięcie z listy członków byłoby cofnięte jednym żądaniem. Moderacja sprowadzała się do wyścigu na kliknięcia.
- **Moderować mógł jeden człowiek.** Właściciel był jedynym punktem, przez który musiało przejść wszystko.

Osobno: pierwsza rola w systemie (`is_admin`, ADR 0006) obsługiwała jeden endpoint analityki i nie miała czym zarządzać.

## Decyzja

Moderacja jest **społecznościowa, nie globalna** — rozbudowujemy uprawnienia, które już istnieją, zamiast budować równoległą władzę nad całą aplikacją.

- **Rola `MODERATOR`** obok `OWNER` i `MEMBER`. Moderator kasuje cudze posty i banuje. Role rozdaje **wyłącznie właściciel**.
- **Bany** (`community_bans`, migracja `V17`): usuwają członkostwo i blokują ponowne dołączenie oraz komentowanie.
- **Kolejka zgłoszeń** (`post_reports`): `POST /api/v1/posts/{postId}/reports` dla każdego zalogowanego, `GET`/`dismissal` pod `/api/v1/communities/{communityId}/post-reports` dla moderatorów.

## Uzasadnienie

- **Ban odcina od pisania, nie od czytania.** Społeczności są w pełni publiczne (§8), więc ukrywanie treści przed zbanowanym byłoby ochroną pozorną — wystarczy się wylogować. Realna szkoda idzie ze ścieżek zapisu i to je odcinamy: publikowanie i komentowanie. Lajki i udostępnienia zostają, bo to czyste liczby bez tożsamości i bez treści — dokładnie ta sama granica, którą §9 wyznacza przy blokadach.
- **Sam ban musi blokować powrót**, inaczej nie jest banem. Usunięcie z listy członków cofa się jednym `PUT /membership`. To jest jedyny powód, dla którego `community_bans` jest osobną tabelą, a nie flagą na członkostwie: wiersz musi przetrwać usunięcie członkostwa.
- **Role rozdaje tylko właściciel.** Moderator mogący nadawać role mógłby zdegradować właściciela i przejąć społeczność. Z tego samego powodu moderator nie banuje moderatora (mogliby wyrzucić się wzajemnie, a rozstrzygałaby kolejność kliknięć), a właściciela nie da się zbanować ani zdegradować — także własną ręką, bo to zostawiłoby społeczność bez właściciela.
- **`OWNER` nie jest osiągalny przez zmianę roli.** Właścicielstwo to osobne pole na `communities`, nie stopień w tej samej skali. Dopuszczenie go tutaj dałoby dwa źródła prawdy o tym, kto jest właścicielem; przekazanie społeczności to osobna operacja, dziś nieistniejąca.
- **Ban należy do społeczności, nie do osoby, która go nałożyła.** Dlatego `banned_by` jest nullable i przy kasowaniu konta moderatora **zerujemy autora zamiast kasować ban**. Gdyby bany znikały razem z jego kontem, odejście moderatora po cichu wpuszczałoby z powrotem wszystkich, których zbanował. Ta sama zasada dotyczy `post_reports.reviewed_by`: odejście moderatora nie cofa rozstrzygnięć, które podjął.
- **Jeden zgłaszający = jedno zgłoszenie na post** (`UNIQUE (post_id, reporter_id)`). Bez tego jedna osoba podbijałaby licznik w nieskończoność, a kolejka pokazywałaby jej upór zamiast liczby niezależnych osób, którym coś przeszkadza. `reportCount` w odpowiedzi jest ważniejszy od pojedynczego zgłoszenia: jedno bywa złośliwe, kilkanaście raczej nie.
- **Zgłaszać można wyłącznie posty w społecznościach.** Zgłoszenie musi mieć adresata, a przy zwykłym poście nie ma dziś nikogo, kto miałby prawo je rozpatrzyć. To bezpośrednia konsekwencja wyboru „społecznościowa zamiast globalnej" i najbardziej odczuwalne jego ograniczenie.
- **Zgłoszenia postów niewidocznych dla zgłaszającego nie przechodzą.** Post zablokowanego autora jest dla niego nieistniejący (§9, 404) — zgłaszanie nie może być obejściem tej zasłony.
- **Tabela `post_reports` należy do modułu content**, mimo że kolejkę ogląda moderator społeczności. To content jest właścicielem postów i tylko on może mieć do nich klucz obcy (§2). Uprawnienie sprawdzamy przez `CommunityMembership.canModerate(...)`, czyli publiczny interfejs modułu community — bez zaglądania w jego tabele.
- **Nie ma przycisku „uwzględnij zgłoszenie".** Uwzględnienie *jest* skasowaniem posta, które moderator już umie (`DELETE /api/v1/posts/{postId}`) i które zabiera zgłoszenia kaskadą. Osobny przycisk byłby drugą ścieżką do tego samego skutku.

## Pułapka: kasowanie wsadowe jako warunek poprawności

Test kasowania konta wyłapał błąd, którego nie widać przy czytaniu kodu.

Gdy usuwany użytkownik jest właścicielem społeczności, jej skasowanie usuwa posty, a te **kaskadowo** swoje zgłoszenia. Jeśli w tej samej transakcji `deleteByReporterId` był wariantem wyprowadzonym (Spring Data wczytuje encje i kasuje je dopiero przy flushu), to kasowanie trafiało na wiersze zabrane wcześniej przez kaskadę — Hibernate zgłaszał `Unexpected row count (expected 1 but was 0)` i **całe usunięcie konta kończyło się 500-tką**. To samo dotyczyło banów.

Dlatego oba są zapytaniami wsadowymi (`@Modifying @Query`): idą do bazy natychmiast i są odporne na kolejność. Ostrzeżenie z §8 o kasowaniu wsadowym tu nie obowiązuje — dotyczy encji, na których cyklu życia wiszą zdarzenia, a na zgłoszeniach i banach nie wisi nic.

## Konsekwencje

- **`CommunityMembership` rośnie o `canModerate` i `isBanned`.** `isOwner` zostaje, bo to nadal osobne pytanie: „kto skasuje cudzy post" (moderatorzy) to nie to samo co „kto skasuje całą społeczność" (wyłącznie właściciel). Moduł content przeszedł z `isOwner` na `canModerate` przy kasowaniu postów.
- **`CommunityRole` zyskuje wartość**, więc klient mobilny musi umieć obsłużyć `MODERATOR` w `myRole` i na liście członków. Poza tym zmiana jest addytywna — żaden istniejący endpoint nie zmienia kontraktu.
- **Zwykłych postów nie da się zgłosić.** To świadomy koszt wyboru zakresu. Gdy pojawi się potrzeba moderacji globalnej, `is_admin` z ADR 0006 jest gotowym punktem zaczepienia, a `post_reports` nie wymaga zmian — trzeba tylko drugiego widoku kolejki, bez warunku na `community_id`.
- **Kolejka nie jest archiwum decyzji.** Skasowany post zabiera swoje zgłoszenia, więc nie zostaje ślad „usunięto po zgłoszeniu". Ślad audytowy wymagałby zdjęcia kaskady i przechowywania kopii treści — czyli osobnej decyzji, także co do RODO.
- **Nie ma powiadomień o decyzjach moderacyjnych.** Zbanowany dowiaduje się o banie dopiero przy próbie napisania czegoś, a zgłaszający nie dostaje informacji zwrotnej. Moduł `notification` istnieje i jest naturalnym miejscem, gdy okaże się to potrzebne.
- **Bany nie wygasają.** Ban czasowy wymaga kolumny `expires_at` i sprzątania, ale przede wszystkim odpowiedzi na pytanie, czy wygasły ban ma wracać cicho, czy z powiadomieniem — to decyzja produktowa, nie techniczna.
