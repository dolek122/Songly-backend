package com.songly.content;

import com.songly.common.media.ImageType;
import com.songly.common.media.UnsupportedImageException;
import com.songly.common.storage.ObjectStorage;
import com.songly.community.CommunityMembership;
import com.songly.content.dto.CommentResponse;
import com.songly.content.dto.PostImageResponse;
import com.songly.content.dto.PostReportResponse;
import com.songly.content.dto.PostResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(ReportProperties.class)
class ContentService {

	private final PostRepository postRepository;
	private final LikeRepository likeRepository;
	private final CommentRepository commentRepository;
	private final ShareRepository shareRepository;
	private final PostReportRepository reportRepository;
	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;
	private final CommunityMembership communityMembership;
	private final ObjectStorage objectStorage;
	private final ApplicationEventPublisher eventPublisher;
	private final ReportProperties reportProperties;
	private final MeterRegistry meterRegistry;

	/**
	 * Prefiks kluczy zdjęć postów. Stała, bo ten sam ciąg musi być użyty przy
	 * budowaniu klucza i przy sprawdzaniu własności — rozjazd między nimi
	 * otworzyłby dokładnie tę dziurę, którą sprawdzenie ma zamykać.
	 */
	private static final String POST_IMAGE_PREFIX = "posts/";

	/**
	 * W Prometheusie: {@code songly_moderation_report_rejected_total{reason="..."}}
	 * (ADR 0019). Obie serie w zdrowym ruchu stoją nisko, więc liczy się przyrost,
	 * nie wartość: {@code rate-limit} to ktoś zasypujący kolejkę zgłoszeniami,
	 * {@code banned} to zbanowany dobijający się kanałem, który właśnie stracił.
	 * Bez licznika trafienie w którąkolwiek bramkę jest z zewnątrz niewidoczne —
	 * dokładnie ten sam powód, dla którego liczymy odrzucenia rate limitera (§13).
	 */
	private static final String REPORT_REJECTED_METRIC = "songly.moderation.report.rejected";

	ContentService(PostRepository postRepository, LikeRepository likeRepository, CommentRepository commentRepository,
			ShareRepository shareRepository, PostReportRepository reportRepository, ProfileDirectory profileDirectory,
			SocialGraph socialGraph, CommunityMembership communityMembership, ObjectStorage objectStorage,
			ApplicationEventPublisher eventPublisher, ReportProperties reportProperties, MeterRegistry meterRegistry) {
		this.postRepository = postRepository;
		this.likeRepository = likeRepository;
		this.commentRepository = commentRepository;
		this.shareRepository = shareRepository;
		this.reportRepository = reportRepository;
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
		this.communityMembership = communityMembership;
		this.objectStorage = objectStorage;
		this.eventPublisher = eventPublisher;
		this.reportProperties = reportProperties;
		this.meterRegistry = meterRegistry;
	}

	@Transactional
	PostResponse createPost(UUID authorId, String content, String imageKey) {
		return createPost(authorId, content, imageKey, null);
	}

	/**
	 * Wgrywa zdjęcie i zwraca jego klucz, jeszcze przed opublikowaniem posta.
	 *
	 * <p>
	 * Klucz zawiera identyfikator wgrywającego, bo to jedyne, co pozwala później
	 * sprawdzić, że dołączane zdjęcie należy do autora posta. Bez tego wystarczyłby
	 * cudzy klucz w {@code imageKey}, żeby przypiąć do swojego posta czyjeś zdjęcie
	 * — a przy usunięciu tego posta skasować właścicielowi plik.
	 */
	@Transactional(readOnly = true)
	PostImageResponse uploadPostImage(UUID authorId, byte[] content) {
		ImageType type = ImageType.detect(content).orElseThrow(UnsupportedImageException::new);
		String key = "%s%s/%s.%s".formatted(POST_IMAGE_PREFIX, authorId, UUID.randomUUID(), type.extension());
		objectStorage.put(key, content, type.contentType());
		return new PostImageResponse(key, objectStorage.publicUrl(key));
	}

	/**
	 * Zdjęcie musi pochodzić z wgrania zrobionego przez tego samego użytkownika.
	 * Sprawdzenie jest prefiksem klucza, a nie osobną tabelą wgrań: klucz i tak
	 * musi być unikalny i i tak zawiera właściciela, więc dodatkowy wiersz
	 * przechowywałby informację, którą już mamy.
	 */
	private String validatedImageKey(UUID authorId, String imageKey) {
		if (imageKey == null || imageKey.isBlank()) {
			return null;
		}
		if (!imageKey.startsWith(POST_IMAGE_PREFIX + authorId + "/")) {
			throw new UnknownPostImageException();
		}
		return imageKey;
	}

	/**
	 * {@code communityId == null} to zwykły post. Publikacja w społeczności wymaga
	 * członkostwa — dołączenie jest otwarte i natychmiastowe, więc to nie bariera,
	 * a wymóg, żeby pisanie w społeczności było świadomą decyzją.
	 */
	@Transactional
	PostResponse createPost(UUID authorId, String content, String imageKey, UUID communityId) {
		if (communityId != null) {
			if (!communityMembership.exists(communityId)) {
				throw new UnknownCommunityException();
			}
			if (!communityMembership.isMember(communityId, authorId)) {
				throw new NotCommunityMemberException();
			}
		}
		Post post = new Post(UUID.randomUUID(), authorId, content, Instant.now(), communityId,
				validatedImageKey(authorId, imageKey));
		postRepository.save(post);
		String displayName = profileDirectory.displayNames(Set.of(authorId)).get(authorId);
		String avatarUrl = profileDirectory.avatarUrls(Set.of(authorId)).get(authorId);
		return new PostResponse(post.getId(), authorId, displayName, avatarUrl, post.getContent(), imageUrl(post),
				post.getCreatedAt(), communityId, 0, false, 0, 0, false);
	}

	private String imageUrl(Post post) {
		return post.getImageKey() == null ? null : objectStorage.publicUrl(post.getImageKey());
	}

	/**
	 * Zgłasza pliki do skasowania po commicie. Osobna metoda, bo wołają ją cztery
	 * różne ścieżki kasowania i każda z nich, gdyby o tym zapomniała, zostawiałaby
	 * pliki w magazynie już na zawsze — bez żadnego wiersza, po którym dałoby się
	 * je odnaleźć.
	 */
	private void reportOrphanedImages(Collection<String> keys) {
		keys.forEach(key -> eventPublisher.publishEvent(new OrphanedPostImageEvent(key)));
	}

	/**
	 * Post zablokowanego autora zgłaszamy jako <b>nieistniejący</b>, nie jako 403.
	 * 403 potwierdzałoby, że post istnieje i że ktoś konkretny nas zablokował — to
	 * ta sama zasada, którą moduł social stosuje już do cudzych zaproszeń.
	 */
	@Transactional(readOnly = true)
	PostResponse getPost(UUID postId, UUID viewerId) {
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		Post post = postRepository.findById(postId).filter(p -> !hiddenAuthors.contains(p.getAuthorId()))
				.orElseThrow(PostNotFoundException::new);
		return toResponses(List.of(post), viewerId, hiddenAuthors).get(0);
	}

	/**
	 * Bez postów społecznościowych — te należą do swojej społeczności, nie do
	 * publicznej listy ani osi czasu autora.
	 *
	 * <p>
	 * Oś czasu konkretnego autora nie potrzebuje filtra w zapytaniu — cała strona
	 * ma jednego autora, więc wystarczy jedno sprawdzenie i pusta strona zamiast
	 * niego.
	 */
	@Transactional(readOnly = true)
	Page<PostResponse> listPosts(UUID authorId, UUID viewerId, Pageable pageable) {
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		if (authorId != null) {
			return hiddenAuthors.contains(authorId)
					? Page.empty(pageable)
					: mapToResponses(
							postRepository.findByAuthorIdAndCommunityIdIsNullOrderByCreatedAtDesc(authorId, pageable),
							viewerId, hiddenAuthors);
		}
		return mapToResponses(
				postRepository.findByCommunityIdIsNullAndAuthorIdNotInOrderByCreatedAtDesc(hiddenAuthors, pageable),
				viewerId, hiddenAuthors);
	}

	/**
	 * Posty jednej społeczności. Odczyt jest otwarty dla każdego zalogowanego, tak
	 * jak sama społeczność (listowanie, podglądanie i dołączanie nie wymagają dziś
	 * zgody właściciela) — ukrywanie treści przed nie-członkami byłoby ochroną
	 * pozorną, skoro dołączenie to jedno żądanie.
	 */
	@Transactional(readOnly = true)
	Page<PostResponse> listCommunityPosts(UUID communityId, UUID viewerId, Pageable pageable) {
		if (!communityMembership.exists(communityId)) {
			throw new UnknownCommunityException();
		}
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		return mapToResponses(postRepository.findByCommunityIdAndAuthorIdNotInOrderByCreatedAtDesc(communityId,
				hiddenAuthors, pageable), viewerId, hiddenAuthors);
	}

	/**
	 * Prosty feed MVP: posty napisane albo udostępnione przez znajomych i mnie,
	 * chronologicznie wg utworzenia posta. Zapytanie po liście userów wystarczy
	 * przy setkach znajomych; przy realnym ruchu to pierwszy kandydat na
	 * read-replikę / precomputed feed (Claude.md §10).
	 */
	@Transactional(readOnly = true)
	Page<PostResponse> feed(UUID viewerId, Pageable pageable) {
		Set<UUID> authorIds = new HashSet<>(socialGraph.friendIds(viewerId));
		authorIds.add(viewerId);
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		return mapToResponses(postRepository.findFeedPosts(authorIds, hiddenAuthors, pageable), viewerId,
				hiddenAuthors);
	}

	/**
	 * Autor kasuje swoje posty wszędzie; dodatkowo <b>właściciel społeczności
	 * kasuje dowolny post w swojej społeczności</b> — to minimum moderacji, bez
	 * którego społeczność nie ma żadnej obrony przed spamem (Claude.md §8).
	 */
	@Transactional
	void deletePost(UUID postId, UUID requesterId) {
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
		if (!post.getAuthorId().equals(requesterId) && !moderates(post, requesterId)) {
			throw new NotResourceOwnerException();
		}
		if (post.getImageKey() != null) {
			reportOrphanedImages(List.of(post.getImageKey()));
		}
		if (!post.getAuthorId().equals(requesterId)) {
			// Tylko przy usunięciu cudzego posta — skasowanie własnego nie jest decyzją
			// moderacyjną i nie ma o czym zawiadamiać.
			//
			// Zgłoszenia liczymy PRZED kasowaniem: znikają razem z postem (kaskada), więc
			// po fakcie nie ma już czego zliczyć i nie dałoby się odróżnić usunięcia
			// zamykającego zgłoszenie od usunięcia z własnej inicjatywy (ADR 0024).
			eventPublisher.publishEvent(new CommunityPostRemovedEvent(post.getCommunityId(), post.getAuthorId(),
					requesterId, (int) reportRepository.countByPostId(postId), Instant.now()));
		}
		postRepository.delete(post);
	}

	/**
	 * Od ADR 0011 moderuje właściciel <b>i</b> moderatorzy — stąd
	 * {@code canModerate} zamiast {@code isOwner}. To nadal dotyczy wyłącznie
	 * postów w społecznościach: przy zwykłych postach chroni samo autorstwo (§8).
	 */
	private boolean moderates(Post post, UUID requesterId) {
		return post.getCommunityId() != null && communityMembership.canModerate(post.getCommunityId(), requesterId);
	}

	/**
	 * Zgłasza post do moderacji społeczności.
	 *
	 * <p>
	 * Wyłącznie posty w społecznościach — zgłoszenie musi mieć adresata, a przy
	 * zwykłym poście nie ma dziś nikogo, kto miałby prawo je rozpatrzyć (świadomy
	 * zakres ADR 0011: moderacja jest społecznościowa, nie globalna).
	 *
	 * <p>
	 * Post niewidoczny dla zgłaszającego (blokada, §9) jest dla niego
	 * nieistniejący, więc {@code visiblePost} daje 404 — zgłaszanie nie może być
	 * obejściem tej zasłony.
	 */
	@Transactional
	void reportPost(UUID postId, UUID reporterId, ReportReason reason, String details) {
		Post post = visiblePost(postId, reporterId);
		if (post.getCommunityId() == null) {
			throw new PostNotInCommunityException();
		}
		if (post.getAuthorId().equals(reporterId)) {
			throw new CannotReportOwnPostException();
		}
		// Zbanowany nie zgłasza (ADR 0018). Ban odcina ścieżki, które docierają do
		// ludzi, a zostawia te będące czystymi liczbami (§8) — zgłoszenie niesie
		// tożsamość i tekst prosto do moderatorów, więc należy do pierwszej grupy.
		if (communityMembership.isBanned(post.getCommunityId(), reporterId)) {
			meterRegistry.counter(REPORT_REJECTED_METRIC, "reason", "banned").increment();
			throw new BannedFromCommunityException();
		}
		// Jeden zgłaszający = jedno zgłoszenie. Inaczej jedna osoba podbijałaby
		// licznik w nieskończoność, a kolejka pokazywałaby upór zamiast liczby
		// niezależnych osób, którym coś przeszkadza.
		if (reportRepository.existsByPostIdAndReporterId(postId, reporterId)) {
			throw new PostAlreadyReportedException();
		}
		// ...a to jest bramka w poprzek postów, której tamta nie zastępuje: bez niej
		// jedno konto zapycha kolejkę setką zgłoszeń różnych postów (ADR 0018).
		// Limit zależy od historii zgłaszającego (ADR 0025): każde odrzucone
		// zgłoszenie zabiera część przydziału, ale nigdy całego.
		if (reportRepository.countByReporterIdAndCreatedAtAfter(reporterId,
				Instant.now().minus(reportProperties.getWindow())) >= effectiveReportLimit(reporterId)) {
			meterRegistry.counter(REPORT_REJECTED_METRIC, "reason", "rate-limit").increment();
			throw new ReportRateLimitExceededException();
		}
		boolean firstReport = !reportRepository.existsByPostId(postId);
		Instant now = Instant.now();
		reportRepository.save(new PostReport(UUID.randomUUID(), postId, reporterId, reason, details, now));
		if (firstReport) {
			// Tylko pierwsze zgłoszenie zawiadamia moderatorów — patrz PostReportedEvent.
			eventPublisher.publishEvent(new PostReportedEvent(post.getCommunityId(), postId, reporterId, now));
		}
	}

	/**
	 * Przydział zgłoszeń na okno, pomniejszony o historię odrzuceń (ADR 0025).
	 *
	 * <p>
	 * Liczba odrzuceń jest <b>globalna</b>, a nie per społeczność: zasypywanie
	 * rozłożone na dziesięć społeczności wymyka się każdemu licznikowi liczonemu
	 * osobno dla każdej z nich. Nie jest to przy tym wyciek, bo ta liczba nigdzie
	 * nie wychodzi — służy wyłącznie do odpowiedzi „nie" wobec samego
	 * zgłaszającego.
	 */
	private int effectiveReportLimit(UUID reporterId) {
		long dismissed = reportRepository.countByReporterIdAndStatus(reporterId, ReportStatus.DISMISSED);
		long reduced = reportProperties.getLimit() - dismissed * reportProperties.getDismissalPenalty();
		return (int) Math.max(reportProperties.getMinLimit(), reduced);
	}

	@Transactional(readOnly = true)
	Page<PostReportResponse> listPendingReports(UUID communityId, UUID requesterId, Pageable pageable) {
		requireModerator(communityId, requesterId);
		Page<PostReport> page = reportRepository.findByCommunityIdAndStatus(communityId, ReportStatus.PENDING,
				pageable);
		List<UUID> postIds = page.getContent().stream().map(PostReport::getPostId).distinct().toList();
		if (postIds.isEmpty()) {
			// Pusta kolejka to najczęstszy przypadek w zdrowej społeczności. Wyjście tu
			// oszczędza nie tyle zapytania, co zapytania z pustym IN — a te potrafią
			// zachowywać się różnie i nie ma powodu na tym polegać.
			return Page.empty(pageable);
		}
		Map<UUID, PostResponse> posts = toResponses(postRepository.findAllById(postIds), requesterId, Set.of()).stream()
				.collect(Collectors.toMap(PostResponse::id, Function.identity()));
		Map<UUID, Long> counts = reportRepository.countByPostIdIn(postIds, ReportStatus.PENDING).stream()
				.collect(Collectors.toMap(PostReportRepository.PostIdCount::getPostId,
						PostReportRepository.PostIdCount::getReportCount));
		Set<UUID> reporterIds = page.getContent().stream().map(PostReport::getReporterId).collect(Collectors.toSet());
		Map<UUID, String> reporterNames = profileDirectory.displayNames(reporterIds);
		// Historia zgłaszającego widziana przez moderatora jest ograniczona do JEGO
		// społeczności (ADR 0025) — inaczej kolejka wynosiłaby na wierzch to, co ten
		// człowiek robił w społecznościach, z którymi ten moderator nie ma nic
		// wspólnego (§8: moderacja jest społecznościowa, nie globalna).
		Map<UUID, Long> dismissed = reportRepository
				.countDismissedByReporterIdIn(communityId, reporterIds, ReportStatus.DISMISSED).stream()
				.collect(Collectors.toMap(PostReportRepository.ReporterDismissedCount::getReporterId,
						PostReportRepository.ReporterDismissedCount::getDismissedCount));
		return page.map(report -> new PostReportResponse(report.getId(), report.getReason(), report.getDetails(),
				report.getReporterId(), reporterNames.get(report.getReporterId()), report.getCreatedAt(),
				counts.getOrDefault(report.getPostId(), 0L), dismissed.getOrDefault(report.getReporterId(), 0L),
				posts.get(report.getPostId())));
	}

	/**
	 * Odrzuca zgłoszenie — moderator uznał, że post zostaje.
	 *
	 * <p>
	 * Odpowiednika „uwzględnij" nie ma i to nie jest brak: uwzględnienie zgłoszenia
	 * <i>jest</i> skasowaniem posta, a to już istnieje ({@code DELETE /posts/id}) i
	 * zabiera zgłoszenia kaskadą. Osobny przycisk robiłby drugą, równoległą ścieżkę
	 * do tego samego skutku.
	 */
	@Transactional
	void dismissReport(UUID communityId, UUID reportId, UUID requesterId) {
		requireModerator(communityId, requesterId);
		PostReport report = reportRepository.findById(reportId).orElseThrow(ReportNotFoundException::new);
		Post post = postRepository.findById(report.getPostId()).orElseThrow(ReportNotFoundException::new);
		// Zgłoszenie z innej społeczności zgłaszamy jako nieistniejące, a nie jako
		// brak uprawnień: 403 potwierdzałoby, że taki identyfikator istnieje.
		if (!communityId.equals(post.getCommunityId())) {
			throw new ReportNotFoundException();
		}
		Instant now = Instant.now();
		report.dismiss(requesterId, now);
		eventPublisher.publishEvent(new PostReportDismissedEvent(communityId, report.getPostId(),
				report.getReporterId(), requesterId, now));
	}

	private void requireModerator(UUID communityId, UUID requesterId) {
		if (!communityMembership.exists(communityId)) {
			throw new UnknownCommunityException();
		}
		if (!communityMembership.canModerate(communityId, requesterId)) {
			throw new NotCommunityModeratorException();
		}
	}

	/**
	 * Sprząta posty usuwanej społeczności. Wołane synchronicznie z transakcji
	 * modułu community, <b>przed</b> usunięciem wiersza {@code communities} —
	 * {@code posts.community_id} ma do niego klucz obcy (Claude.md §9).
	 */
	@Transactional
	void deleteCommunityData(UUID communityId) {
		// Klucze trzeba odczytać PRZED kasowaniem — po nim nie ma już skąd. Kasowanie
		// zostaje wsadowe: nie wisi na nim żaden cykl życia encji, a liczba postów w
		// społeczności nie ma górnego ograniczenia.
		reportOrphanedImages(postRepository.findImageKeysByCommunityId(communityId));
		postRepository.deleteByCommunityId(communityId);
	}

	private Page<PostResponse> mapToResponses(Page<Post> page, UUID viewerId, Set<UUID> hiddenAuthors) {
		Map<UUID, PostResponse> byId = toResponses(page.getContent(), viewerId, hiddenAuthors).stream()
				.collect(Collectors.toMap(PostResponse::id, Function.identity()));
		return page.map(post -> byId.get(post.getId()));
	}

	@Transactional
	void likePost(UUID postId, UUID userId) {
		Post post = visiblePost(postId, userId);
		if (!likeRepository.existsById(new LikeId(postId, userId))) {
			Instant now = Instant.now();
			likeRepository.save(new Like(postId, userId, now));
			eventPublisher.publishEvent(new PostLikedEvent(postId, post.getAuthorId(), userId, now));
		}
	}

	/**
	 * Cofnięcie celowo <b>nie</b> sprawdza blokady, choć założenie sprawdza:
	 * blokując kogoś po polubieniu jego posta użytkownik musi móc to polubienie
	 * wycofać. Blokada odcina od cudzych treści, a nie zamraża własnych śladów na
	 * nich.
	 */
	@Transactional
	void unlikePost(UUID postId, UUID userId) {
		requirePostExists(postId);
		likeRepository.deleteById(new LikeId(postId, userId));
	}

	@Transactional
	void sharePost(UUID postId, UUID userId) {
		Post post = visiblePost(postId, userId);
		if (!shareRepository.existsById(new ShareId(postId, userId))) {
			Instant now = Instant.now();
			shareRepository.save(new Share(postId, userId, now));
			eventPublisher.publishEvent(new PostSharedEvent(postId, post.getAuthorId(), userId, now));
		}
	}

	@Transactional
	void unsharePost(UUID postId, UUID userId) {
		requirePostExists(postId);
		shareRepository.deleteById(new ShareId(postId, userId));
	}

	@Transactional
	CommentResponse addComment(UUID postId, UUID authorId, String content) {
		Post post = visiblePost(postId, authorId);
		// Ban odcina od pisania w społeczności, a komentarz jest pisaniem. Samo
		// usunięcie z listy członków tego nie łapało: komentowanie wymaga tylko
		// zalogowania, więc zbanowany zaczepiałby dalej — i to jest właśnie ta
		// ścieżka, dla której bany istnieją. Lajki i udostępnienia zostają: to czyste
		// liczby bez tożsamości i bez treści (ta sama zasada co przy blokadach, §9).
		if (post.getCommunityId() != null && communityMembership.isBanned(post.getCommunityId(), authorId)) {
			throw new BannedFromCommunityException();
		}
		Comment comment = new Comment(UUID.randomUUID(), postId, authorId, content, Instant.now());
		commentRepository.save(comment);
		eventPublisher.publishEvent(
				new PostCommentedEvent(postId, post.getAuthorId(), comment.getId(), authorId, comment.getCreatedAt()));
		String displayName = profileDirectory.displayNames(Set.of(authorId)).get(authorId);
		String avatarUrl = profileDirectory.avatarUrls(Set.of(authorId)).get(authorId);
		return new CommentResponse(comment.getId(), postId, authorId, displayName, avatarUrl, comment.getContent(),
				comment.getCreatedAt());
	}

	@Transactional(readOnly = true)
	Page<CommentResponse> listComments(UUID postId, UUID viewerId, Pageable pageable) {
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		visiblePost(postId, hiddenAuthors);
		Page<Comment> page = commentRepository.findByPostIdAndAuthorIdNotInOrderByCreatedAtAsc(postId, hiddenAuthors,
				pageable);
		Set<UUID> authorIds = page.getContent().stream().map(Comment::getAuthorId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(authorIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(authorIds);
		return page.map(comment -> new CommentResponse(comment.getId(), comment.getPostId(), comment.getAuthorId(),
				names.get(comment.getAuthorId()), avatars.get(comment.getAuthorId()), comment.getContent(),
				comment.getCreatedAt()));
	}

	@Transactional
	void deleteComment(UUID postId, UUID commentId, UUID requesterId) {
		Comment comment = commentRepository.findById(commentId).filter(c -> c.getPostId().equals(postId))
				.orElseThrow(CommentNotFoundException::new);
		if (!comment.getAuthorId().equals(requesterId)) {
			throw new NotResourceOwnerException();
		}
		commentRepository.delete(comment);
	}

	/**
	 * Kolejność ma znaczenie: najpierw aktywność usera na CUDZYCH postach
	 * (komentarze/lajki/shares), dopiero potem jego własne posty — usunięcie posta
	 * kaskadowo (ON DELETE CASCADE post_id) zabiera resztę powiązanych wierszy, w
	 * tym od innych userów.
	 */
	@Transactional
	void deleteAccountData(UUID userId) {
		commentRepository.deleteByAuthorId(userId);
		likeRepository.deleteByUserId(userId);
		shareRepository.deleteByUserId(userId);
		// Zgłoszenia ZŁOŻONE przez tego usera znikają — bez zgłaszającego nie ma
		// zgłoszenia. Zgłoszenia przez niego ROZPATRZONE zostają, tracą tylko autora
		// decyzji: odejście moderatora nie może cofać rozstrzygnięć, które podjął.
		reportRepository.deleteByReporterId(userId);
		reportRepository.clearReviewer(userId);
		reportOrphanedImages(postRepository.findImageKeysByAuthorId(userId));
		postRepository.deleteByAuthorId(userId);
	}

	private void requirePostExists(UUID postId) {
		if (!postRepository.existsById(postId)) {
			throw new PostNotFoundException();
		}
	}

	/**
	 * Autorzy niewidoczni dla {@code viewerId}: blokada w którymkolwiek kierunku
	 * chowa obie strony przed sobą nawzajem, więc zablokowany traci widok tak samo
	 * jak blokujący. Jedno zapytanie na żądanie — wynik wędruje w dół zamiast być
	 * pobierany osobno przez każdy filtr.
	 */
	private Set<UUID> hiddenAuthorsFor(UUID viewerId) {
		return socialGraph.blockedEitherWayIds(viewerId);
	}

	/**
	 * Post widziany oczami {@code viewerId} — dla ścieżek zapisu (lajk,
	 * udostępnienie, komentarz). To one, a nie odczyty, są prawdziwym powodem
	 * istnienia blokady: każda z nich kończy się powiadomieniem u autora, więc bez
	 * tego sprawdzenia zablokowany dalej mógłby zaczepiać blokującego pod jego
	 * własnym postem.
	 */
	private Post visiblePost(UUID postId, UUID viewerId) {
		return visiblePost(postId, hiddenAuthorsFor(viewerId));
	}

	private Post visiblePost(UUID postId, Set<UUID> hiddenAuthors) {
		return postRepository.findById(postId).filter(post -> !hiddenAuthors.contains(post.getAuthorId()))
				.orElseThrow(PostNotFoundException::new);
	}

	private List<PostResponse> toResponses(List<Post> posts, UUID viewerId, Set<UUID> hiddenAuthors) {
		List<UUID> postIds = posts.stream().map(Post::getId).toList();
		if (postIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, Long> likeCounts = likeRepository.countByPostIdIn(postIds).stream().collect(
				Collectors.toMap(LikeRepository.PostIdCount::getPostId, LikeRepository.PostIdCount::getLikeCount));
		Map<UUID, Long> commentCounts = commentRepository.countByPostIdIn(postIds, hiddenAuthors).stream()
				.collect(Collectors.toMap(CommentRepository.PostIdCount::getPostId,
						CommentRepository.PostIdCount::getCommentCount));
		Map<UUID, Long> shareCounts = shareRepository.countByPostIdIn(postIds).stream().collect(
				Collectors.toMap(ShareRepository.PostIdCount::getPostId, ShareRepository.PostIdCount::getShareCount));
		Set<UUID> likedByViewer = likeRepository.findByPostIdInAndUserId(postIds, viewerId).stream()
				.map(Like::getPostId).collect(Collectors.toSet());
		Set<UUID> sharedByViewer = shareRepository.findByPostIdInAndUserId(postIds, viewerId).stream()
				.map(Share::getPostId).collect(Collectors.toSet());
		Set<UUID> authorIds = posts.stream().map(Post::getAuthorId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory.displayNames(authorIds);
		Map<UUID, String> avatars = profileDirectory.avatarUrls(authorIds);
		return posts.stream()
				.map(post -> new PostResponse(post.getId(), post.getAuthorId(), names.get(post.getAuthorId()),
						avatars.get(post.getAuthorId()), post.getContent(), imageUrl(post), post.getCreatedAt(),
						post.getCommunityId(), likeCounts.getOrDefault(post.getId(), 0L),
						likedByViewer.contains(post.getId()), commentCounts.getOrDefault(post.getId(), 0L),
						shareCounts.getOrDefault(post.getId(), 0L), sharedByViewer.contains(post.getId())))
				.collect(Collectors.toList());
	}
}
