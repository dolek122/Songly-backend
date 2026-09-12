package com.songly.identity;

import com.songly.identity.dto.AdminListResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nadawanie i odbieranie roli administratora (ADR 0021). Do tej pory promocja
 * była ręcznym {@code UPDATE} w bazie — ADR 0006 zapisał to jako stan
 * tymczasowy „do czasu, aż powstanie panel admina".
 *
 * <p>
 * <b>Pierwszy administrator nadal musi powstać w bazie</b> i to jest
 * nieusuwalne z natury problemu: nadawać może wyłącznie administrator, więc
 * skądś musi wziąć się ten pierwszy. Ten serwis likwiduje ręczny SQL jako
 * <i>procedurę operacyjną</i>, nie jako jednorazowy bootstrap.
 */
@Service
class AdminRoleService implements AdminDirectory {

	private static final Logger log = LoggerFactory.getLogger(AdminRoleService.class);

	/**
	 * W Prometheusie: {@code songly_admin_role_changes_total{action="..."}}. Seria,
	 * która w zdrowym ruchu praktycznie stoi — eskalacja uprawnień jest zdarzeniem
	 * rzadkim, więc każdy przyrost wart jest zauważenia, zwłaszcza taki, którego
	 * nikt się nie spodziewał (ta sama logika co {@code outcome="reuse"} przy
	 * refresh tokenach, Claude.md §13).
	 */
	private static final String ROLE_CHANGES_METRIC = "songly.admin.role.changes";

	private final UserRepository userRepository;
	private final MeterRegistry meterRegistry;

	AdminRoleService(UserRepository userRepository, MeterRegistry meterRegistry) {
		this.userRepository = userRepository;
		this.meterRegistry = meterRegistry;
	}

	/**
	 * Pełny wykaz, <b>bez paginacji</b> — wbrew konwencji z Claude.md §6 i
	 * świadomie. Zbiór administratorów jest z natury mały, a celem tej listy jest
	 * audyt „kto ma władzę"; stronicowanie dawałoby tu możliwość przeoczenia kogoś
	 * na drugiej stronie, czyli psułoby dokładnie to, po co lista istnieje.
	 */
	@Transactional(readOnly = true)
	AdminListResponse admins() {
		return new AdminListResponse(
				userRepository.findByAdminTrueOrderByCreatedAtAsc().stream().map(User::getId).toList());
	}

	/**
	 * Adresaci powiadomień administracyjnych (ADR 0023) — ta sama lista, tylko bez
	 * opakowania w DTO, bo konsumentem jest inny moduł, a nie kontroler.
	 */
	@Override
	@Transactional(readOnly = true)
	public Set<UUID> adminIds() {
		return userRepository.findByAdminTrueOrderByCreatedAtAsc().stream().map(User::getId)
				.collect(Collectors.toSet());
	}

	/** Idempotentne — nadanie uprawnień komuś, kto je ma, nie jest błędem. */
	@Transactional
	void grant(UUID targetUserId, UUID requesterId) {
		User target = userRepository.findById(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (target.isAdmin()) {
			return;
		}
		target.changeAdmin(true);
		// Bez adresów e-mail — identyfikujemy po userId, jak cała reszta śladu
		// audytowego (Claude.md §13).
		log.warn("Nadano uprawnienia administratora użytkownikowi {} (wykonał {})", targetUserId, requesterId);
		meterRegistry.counter(ROLE_CHANGES_METRIC, "action", "granted").increment();
	}

	/**
	 * Idempotentne wobec kogoś, kto uprawnień nie ma. <b>Nie wobec siebie</b>:
	 * zakaz degradowania samego siebie jest tym, co gwarantuje, że w systemie
	 * zawsze zostanie co najmniej jeden administrator — patrz
	 * {@link CannotRevokeOwnAdminException}.
	 */
	@Transactional
	void revoke(UUID targetUserId, UUID requesterId) {
		if (targetUserId.equals(requesterId)) {
			throw new CannotRevokeOwnAdminException();
		}
		User target = userRepository.findById(targetUserId).orElseThrow(AdminUserNotFoundException::new);
		if (!target.isAdmin()) {
			return;
		}
		target.changeAdmin(false);
		// Skutek jest natychmiastowy i nie wymaga unieważniania sesji: uprawnienie
		// czytamy na żywo z bazy przy każdym żądaniu (ADR 0006), a nie z tokenu.
		log.warn("Odebrano uprawnienia administratora użytkownikowi {} (wykonał {})", targetUserId, requesterId);
		meterRegistry.counter(ROLE_CHANGES_METRIC, "action", "revoked").increment();
	}
}
