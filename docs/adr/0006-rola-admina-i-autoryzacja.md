# 6. Pierwsza rola w systemie: flaga `is_admin` + autoryzacja na poziomie request matcherów

Data: 2026-07-20
Status: zaakceptowane

## Kontekst

ADR 0004 świadomie zostawił `GET /api/v1/analytics/summary` dostępny dla każdego zalogowanego użytkownika, z zastrzeżeniem: "warto dodać gating rolą, gdy pojawi się panel admina". W systemie do tej pory nie istniał żaden koncept roli/uprawnień poza samym uwierzytelnieniem (JWT) — `SecurityConfig` rozróżniał tylko `permitAll()` i `authenticated()`.

## Decyzja

Dodajemy pierwszą, minimalną rolę: boolean `is_admin` na encji `User` (migracja `V10__user_is_admin.sql`, domyślnie `FALSE`, bez API do samodzielnego nadawania sobie tej flagi — jedyny sposób na promocję to bezpośrednia zmiana w bazie przez operatora, dopóki nie powstanie panel admina). Status admina jest sprawdzany **na żywo z bazy przy każdym requeście** w `JwtAuthenticationFilter`, a nie zaszywany jako claim w JWT — przez nowy, jednometodowy serwis `AdminStatusService` (publiczny w `com.songly.identity`), który jest jedynym punktem, przez który pakiet `identity.security` dotyka informacji o userze (bez dostępu do encji `User` ani do `UserRepository` wprost — Claude.md §2). `SecurityConfig` dostaje nową regułę `requestMatchers(GET, "/api/v1/analytics/summary").hasAuthority("ROLE_ADMIN")` przed generalnym `anyRequest().authenticated()`, w tym samym stylu co istniejące reguły `permitAll()`. Brak dostępu kończy się 403 z tym samym formatem JSON (`ApiError`) co 401 — nowy `RestAccessDeniedHandler`, modelowany 1:1 na istniejącym `RestAuthenticationEntryPoint`.

## Uzasadnienie

- **Świeżość ponad wydajność**: sprawdzanie w bazie przy każdym requeście (zamiast claimu w JWT) oznacza, że odebranie komuś roli admina działa natychmiast, a nie dopiero po wygaśnięciu access tokenu (15 min). Przy jednym dodatkowym odczycie z bazy na request (rząd wielkości podobny do innych operacji w tej aplikacji) to uzasadniony kompromis dla operacji administracyjnej, która nie jest na gorącej ścieżce (Claude.md nie ma jeszcze wymogu ultra-niskiej latencji dla żadnego endpointu).
- **Brak nowych frameworków**: żadnego `@PreAuthorize`/`@EnableMethodSecurity`, `UserDetailsService` ani `spring-security-test` — autoryzacja pozostaje w całości w `authorizeHttpRequests` w `SecurityConfig`, jedynym miejscu, gdzie ten projekt już podejmuje takie decyzje. Rozszerzenie istniejącego wzorca jest mniejszym ryzykiem niż wprowadzenie równoległego mechanizmu.
- **Encja `User` nie wycieka poza moduł `identity`** nawet do własnego podpakietu `identity.security` — `AdminStatusService` zwraca `boolean`, nie encję ani DTO z danymi usera. To ta sama zasada modularności co między odrębnymi modułami (Claude.md §2), zastosowana konsekwentnie także wewnątrz jednego modułu.
- Boolean zamiast enuma ról: na dziś system rozróżnia tylko "admin / nie-admin". Wprowadzanie enuma `Role` z jedną realną wartością byłoby premature — jeśli pojawi się druga rola (np. moderator), to nowa decyzja i przejście na `role VARCHAR(20)` (mirror `CommunityRole`, ADR do napisania wtedy).

## Konsekwencje

- Nie ma dziś żadnego sposobu na utworzenie pierwszego admina inaczej niż ręcznym `UPDATE users SET is_admin = TRUE WHERE ...` w bazie. To świadomie pozostaje poza zakresem tej zmiany — samoobsługowe nadawanie sobie roli admina przy rejestracji byłoby luką bezpieczeństwa, a pełny panel/proces promocji to osobna funkcjonalność do zaprojektowania, gdy będzie potrzebna.
- Każdy kolejny endpoint wymagający uprawnień admina dostaje kolejną linijkę `requestMatchers(...).hasAuthority("ROLE_ADMIN")` w `SecurityConfig` — nie trzeba nic zmieniać w `AdminStatusService` ani `JwtAuthenticationFilter`.
- Jeśli w przyszłości pojawi się potrzeba autoryzacji per-zasób (nie tylko per-endpoint), np. "user może edytować tylko swój post", obecny mechanizm (request matcher + `GrantedAuthority`) już dziś nie wystarczy — to pozostaje logiką na poziomie serwisu (jak już się dzieje np. w `content`), a nie coś do rozwiązania w `SecurityConfig`.
