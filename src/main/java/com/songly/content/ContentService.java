package com.songly.content;

import com.songly.common.storage.ObjectStorage;
import com.songly.community.CommunityMembership;
import com.songly.identity.SuspendedAccounts;
import com.songly.content.dto.CommentResponse;
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
import java.util.Objects;
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
	private final SuspendedAccounts suspendedAccounts;
	private final ReporterDecisionRepository decisionRepository;
	private final PostImageUploads postImageUploads;

	private static final String REPORT_REJECTED_METRIC = "songly.moderation.report.rejected";

	ContentService(PostRepository postRepository, LikeRepository likeRepository, CommentRepository commentRepository,
			ShareRepository shareRepository, PostReportRepository reportRepository, ProfileDirectory profileDirectory,
			SocialGraph socialGraph, CommunityMembership communityMembership, ObjectStorage objectStorage,
			ApplicationEventPublisher eventPublisher, ReportProperties reportProperties, MeterRegistry meterRegistry,
			SuspendedAccounts suspendedAccounts, ReporterDecisionRepository decisionRepository,
			PostImageUploads postImageUploads) {
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
		this.suspendedAccounts = suspendedAccounts;
		this.decisionRepository = decisionRepository;
		this.postImageUploads = postImageUploads;
	}

	@Transactional
	PostResponse createPost(UUID authorId, String content, String imageKey) {
		return createPost(authorId, content, imageKey, null);
	}

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
				postImageUploads.claim(authorId, imageKey));
		postRepository.save(post);
		String displayName = profileDirectory.displayNames(Set.of(authorId)).get(authorId);
		String avatarUrl = profileDirectory.avatarUrls(Set.of(authorId)).get(authorId);
		return new PostResponse(post.getId(), authorId, displayName, avatarUrl, post.getContent(), imageUrl(post),
				post.getCreatedAt(), communityId, 0, false, 0, 0, false);
	}

	private String imageUrl(Post post) {
		return post.getImageKey() == null ? null : objectStorage.publicUrl(post.getImageKey());
	}

	private void reportOrphanedImages(Collection<String> keys) {
		keys.forEach(key -> eventPublisher.publishEvent(new OrphanedPostImageEvent(key)));
	}

	@Transactional(readOnly = true)
	PostResponse getPost(UUID postId, UUID viewerId) {
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		Post post = postRepository.findById(postId).filter(p -> !hiddenAuthors.contains(p.getAuthorId()))
				.orElseThrow(PostNotFoundException::new);
		return toResponses(List.of(post), viewerId, hiddenAuthors).get(0);
	}

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

	@Transactional(readOnly = true)
	Page<PostResponse> listCommunityPosts(UUID communityId, UUID viewerId, Pageable pageable) {
		if (!communityMembership.exists(communityId)) {
			throw new UnknownCommunityException();
		}
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		return mapToResponses(postRepository.findByCommunityIdAndAuthorIdNotInOrderByCreatedAtDesc(communityId,
				hiddenAuthors, pageable), viewerId, hiddenAuthors);
	}

	@Transactional(readOnly = true)
	Page<PostResponse> feed(UUID viewerId, Pageable pageable) {
		Set<UUID> authorIds = new HashSet<>(socialGraph.friendIds(viewerId));
		authorIds.add(viewerId);
		Set<UUID> hiddenAuthors = hiddenAuthorsFor(viewerId);
		return mapToResponses(postRepository.findFeedPosts(authorIds, hiddenAuthors, pageable), viewerId,
				hiddenAuthors);
	}

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
			Set<UUID> reporterIds = Set.copyOf(reportRepository.findReporterIdsByPostId(postId));
			Instant now = Instant.now();
			recordUpheldReports(post.getCommunityId(), reporterIds, now);
			eventPublisher.publishEvent(new CommunityPostRemovedEvent(post.getCommunityId(), post.getAuthorId(),
					requesterId, reporterIds, now));
		}
		postRepository.delete(post);
	}

	private boolean moderates(Post post, UUID requesterId) {
		return post.getCommunityId() != null && communityMembership.canModerate(post.getCommunityId(), requesterId);
	}

	@Transactional
	void reportPost(UUID postId, UUID reporterId, ReportReason reason, String details) {
		Post post = visiblePost(postId, reporterId);
		if (post.getAuthorId().equals(reporterId)) {
			throw new CannotReportOwnPostException();
		}
		if (post.getCommunityId() != null && communityMembership.isBanned(post.getCommunityId(), reporterId)) {
			meterRegistry.counter(REPORT_REJECTED_METRIC, "reason", "banned").increment();
			throw new BannedFromCommunityException();
		}
		if (reportRepository.existsByPostIdAndReporterId(postId, reporterId)) {
			throw new PostAlreadyReportedException();
		}
		if (reportRepository.countByReporterIdAndCreatedAtAfter(reporterId,
				Instant.now().minus(reportProperties.getWindow())) >= effectiveReportLimit(reporterId)) {
			meterRegistry.counter(REPORT_REJECTED_METRIC, "reason", "rate-limit").increment();
			throw new ReportRateLimitExceededException();
		}
		boolean firstReport = !reportRepository.existsByPostIdAndStatus(postId, ReportStatus.PENDING);
		Instant now = Instant.now();
		reportRepository.save(new PostReport(UUID.randomUUID(), postId, reporterId, reason, details, now));
		if (firstReport) {
			eventPublisher.publishEvent(new PostReportedEvent(post.getCommunityId(), postId, reporterId, now));
		}
	}

	private int effectiveReportLimit(UUID reporterId) {
		Instant since = historyCutoff();
		long dismissed = decisionRepository.countByUserIdAndOutcomeAndDecidedAtAfter(reporterId,
				ReporterDecisionOutcome.DISMISSED, since);
		long upheld = decisionRepository.countByUserIdAndOutcomeAndDecidedAtAfter(reporterId,
				ReporterDecisionOutcome.UPHELD, since);
		long netDismissed = Math.max(0, dismissed - upheld);
		long reduced = reportProperties.getLimit() - netDismissed * reportProperties.getDismissalPenalty();
		return (int) Math.max(reportProperties.getMinLimit(), Math.min(reportProperties.getLimit(), reduced));
	}

	@Transactional(readOnly = true)
	Page<PostReportResponse> listPendingReports(UUID communityId, UUID requesterId, Pageable pageable) {
		requireModerator(communityId, requesterId);
		return pendingReports(reportRepository.findByCommunityIdAndStatus(communityId, ReportStatus.PENDING, pageable),
				communityId, requesterId, pageable);
	}

	@Transactional(readOnly = true)
	Page<PostReportResponse> listPendingPlatformReports(UUID adminId, Pageable pageable) {
		return pendingReports(reportRepository.findOutsideCommunitiesByStatus(ReportStatus.PENDING, pageable), null,
				adminId, pageable);
	}

	private Page<PostReportResponse> pendingReports(Page<PostReport> page, UUID communityId, UUID requesterId,
			Pageable pageable) {
		List<UUID> postIds = page.getContent().stream().map(PostReport::getPostId).distinct().toList();
		if (postIds.isEmpty()) {
			return Page.empty(pageable);
		}
		Map<UUID, PostResponse> posts = toResponses(postRepository.findAllById(postIds), requesterId, Set.of()).stream()
				.collect(Collectors.toMap(PostResponse::id, Function.identity()));
		Map<UUID, Long> counts = reportRepository.countByPostIdIn(postIds, ReportStatus.PENDING).stream()
				.collect(Collectors.toMap(PostReportRepository.PostIdCount::getPostId,
						PostReportRepository.PostIdCount::getReportCount));
		Set<UUID> reporterIds = page.getContent().stream().map(PostReport::getReporterId).collect(Collectors.toSet());
		Map<UUID, String> reporterNames = profileDirectory.displayNames(reporterIds);
		Instant since = historyCutoff();
		Map<UUID, Long> upheld = decisionCounts(communityId, ReporterDecisionOutcome.UPHELD, reporterIds, since);
		Map<UUID, Long> dismissed = decisionCounts(communityId, ReporterDecisionOutcome.DISMISSED, reporterIds, since);
		return page.map(report -> {
			UUID reporterId = report.getReporterId();
			return new PostReportResponse(report.getId(), report.getReason(), report.getDetails(), reporterId,
					reporterNames.get(reporterId), report.getCreatedAt(), counts.getOrDefault(report.getPostId(), 0L),
					upheld.getOrDefault(reporterId, 0L).intValue(), dismissed.getOrDefault(reporterId, 0L).intValue(),
					posts.get(report.getPostId()));
		});
	}

	private Map<UUID, Long> decisionCounts(UUID communityId, ReporterDecisionOutcome outcome, Set<UUID> reporterIds,
			Instant since) {
		List<ReporterDecisionRepository.UserDecisionCount> counts = communityId == null
				? decisionRepository.countOutsideCommunitiesByOutcomeSince(outcome, reporterIds, since)
				: decisionRepository.countByCommunityAndOutcomeSince(communityId, outcome, reporterIds, since);
		return counts.stream().collect(Collectors.toMap(ReporterDecisionRepository.UserDecisionCount::getUserId,
				ReporterDecisionRepository.UserDecisionCount::getDecisionCount));
	}

	@Transactional
	void dismissReport(UUID communityId, UUID reportId, UUID requesterId) {
		requireModerator(communityId, requesterId);
		dismiss(communityId, reportId, requesterId);
	}

	@Transactional
	void dismissPlatformReport(UUID reportId, UUID adminId) {
		dismiss(null, reportId, adminId);
	}

	private void dismiss(UUID communityId, UUID reportId, UUID reviewerId) {
		PostReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow(ReportNotFoundException::new);
		Post post = postRepository.findById(report.getPostId()).orElseThrow(ReportNotFoundException::new);
		if (!Objects.equals(communityId, post.getCommunityId())) {
			throw new ReportNotFoundException();
		}
		if (report.getStatus() != ReportStatus.PENDING) {
			return;
		}
		Instant now = Instant.now();
		report.dismiss(reviewerId, now);
		recordDecision(report.getReporterId(), communityId, ReporterDecisionOutcome.DISMISSED, now);
		eventPublisher.publishEvent(
				new PostReportDismissedEvent(communityId, report.getPostId(), report.getReporterId(), reviewerId, now));
	}

	@Transactional
	void removePlatformPost(UUID postId, UUID adminId) {
		Post post = postRepository.findByIdForUpdate(postId).orElseThrow(PostNotFoundException::new);
		if (post.getCommunityId() != null) {
			throw new PostInCommunityException();
		}
		if (post.getImageKey() != null) {
			reportOrphanedImages(List.of(post.getImageKey()));
		}
		Set<UUID> reporterIds = Set.copyOf(reportRepository.findReporterIdsByPostId(postId));
		Instant now = Instant.now();
		recordUpheldReports(null, reporterIds, now);
		eventPublisher.publishEvent(new PostRemovedEvent(postId, post.getAuthorId(), adminId, reporterIds, now));
		postRepository.delete(post);
	}

	private void recordUpheldReports(UUID communityId, Set<UUID> reporterIds, Instant now) {
		reporterIds.forEach(reporterId -> recordDecision(reporterId, communityId, ReporterDecisionOutcome.UPHELD, now));
	}

	private void recordDecision(UUID userId, UUID communityId, ReporterDecisionOutcome outcome, Instant now) {
		decisionRepository.save(new ReporterDecision(UUID.randomUUID(), userId, communityId, outcome, now));
	}

	private Instant historyCutoff() {
		return Instant.now().minus(reportProperties.getHistoryWindow());
	}

	private void requireModerator(UUID communityId, UUID requesterId) {
		if (!communityMembership.exists(communityId)) {
			throw new UnknownCommunityException();
		}
		if (!communityMembership.canModerate(communityId, requesterId)) {
			throw new NotCommunityModeratorException();
		}
	}

	@Transactional
	void deleteCommunityData(UUID communityId) {
		reportOrphanedImages(postRepository.findImageKeysByCommunityId(communityId));
		postRepository.deleteByCommunityId(communityId);
		decisionRepository.deleteByCommunityId(communityId);
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
		if (comment.getAuthorId().equals(requesterId)) {
			commentRepository.delete(comment);
			return;
		}
		Post post = postRepository.findById(postId).orElseThrow(CommentNotFoundException::new);
		if (!moderates(post, requesterId)) {
			throw new NotResourceOwnerException();
		}
		commentRepository.delete(comment);
		eventPublisher.publishEvent(new CommunityCommentRemovedEvent(post.getCommunityId(), postId,
				comment.getAuthorId(), requesterId, Instant.now()));
	}

	@Transactional
	void deleteAccountData(UUID userId) {
		commentRepository.deleteByAuthorId(userId);
		likeRepository.deleteByUserId(userId);
		shareRepository.deleteByUserId(userId);
		reportRepository.deleteByReporterId(userId);
		decisionRepository.deleteByUserId(userId);
		reportRepository.clearReviewer(userId);
		postImageUploads.deleteAllOf(userId);
		reportOrphanedImages(postRepository.findImageKeysByAuthorId(userId));
		postRepository.deleteByAuthorId(userId);
	}

	private void requirePostExists(UUID postId) {
		if (!postRepository.existsById(postId)) {
			throw new PostNotFoundException();
		}
	}

	private Set<UUID> hiddenAuthorsFor(UUID viewerId) {
		Set<UUID> hidden = new HashSet<>(socialGraph.blockedEitherWayIds(viewerId));
		hidden.addAll(suspendedAccounts.suspendedUserIds());
		return hidden;
	}

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
