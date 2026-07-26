package com.songly.content;

import com.songly.community.CommunityMembership;
import com.songly.content.dto.CommentResponse;
import com.songly.content.dto.PostResponse;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ContentService {

	private final PostRepository postRepository;
	private final LikeRepository likeRepository;
	private final CommentRepository commentRepository;
	private final ShareRepository shareRepository;
	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;
	private final CommunityMembership communityMembership;
	private final ApplicationEventPublisher eventPublisher;

	ContentService(PostRepository postRepository, LikeRepository likeRepository, CommentRepository commentRepository,
			ShareRepository shareRepository, ProfileDirectory profileDirectory, SocialGraph socialGraph,
			CommunityMembership communityMembership, ApplicationEventPublisher eventPublisher) {
		this.postRepository = postRepository;
		this.likeRepository = likeRepository;
		this.commentRepository = commentRepository;
		this.shareRepository = shareRepository;
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
		this.communityMembership = communityMembership;
		this.eventPublisher = eventPublisher;
	}

	@Transactional
	PostResponse createPost(UUID authorId, String content) {
		return createPost(authorId, content, null);
	}

	/**
	 * {@code communityId == null} to zwykły post. Publikacja w społeczności wymaga
	 * członkostwa — dołączenie jest otwarte i natychmiastowe, więc to nie bariera,
	 * a wymóg, żeby pisanie w społeczności było świadomą decyzją.
	 */
	@Transactional
	PostResponse createPost(UUID authorId, String content, UUID communityId) {
		if (communityId != null) {
			if (!communityMembership.exists(communityId)) {
				throw new UnknownCommunityException();
			}
			if (!communityMembership.isMember(communityId, authorId)) {
				throw new NotCommunityMemberException();
			}
		}
		Post post = new Post(UUID.randomUUID(), authorId, content, Instant.now(), communityId);
		postRepository.save(post);
		String displayName = profileDirectory.displayNames(Set.of(authorId)).get(authorId);
		String avatarUrl = profileDirectory.avatarUrls(Set.of(authorId)).get(authorId);
		return new PostResponse(post.getId(), authorId, displayName, avatarUrl, post.getContent(), post.getCreatedAt(),
				communityId, 0, false, 0, 0, false);
	}

	@Transactional(readOnly = true)
	PostResponse getPost(UUID postId, UUID viewerId) {
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
		return toResponses(List.of(post), viewerId).get(0);
	}

	/**
	 * Bez postów społecznościowych — te należą do swojej społeczności, nie do
	 * publicznej listy ani osi czasu autora.
	 */
	@Transactional(readOnly = true)
	Page<PostResponse> listPosts(UUID authorId, UUID viewerId, Pageable pageable) {
		Page<Post> page = authorId == null
				? postRepository.findByCommunityIdIsNullOrderByCreatedAtDesc(pageable)
				: postRepository.findByAuthorIdAndCommunityIdIsNullOrderByCreatedAtDesc(authorId, pageable);
		return mapToResponses(page, viewerId);
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
		return mapToResponses(postRepository.findByCommunityIdOrderByCreatedAtDesc(communityId, pageable), viewerId);
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
		return mapToResponses(postRepository.findFeedPosts(authorIds, pageable), viewerId);
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
		postRepository.delete(post);
	}

	private boolean moderates(Post post, UUID requesterId) {
		return post.getCommunityId() != null && communityMembership.isOwner(post.getCommunityId(), requesterId);
	}

	/**
	 * Sprząta posty usuwanej społeczności. Wołane synchronicznie z transakcji
	 * modułu community, <b>przed</b> usunięciem wiersza {@code communities} —
	 * {@code posts.community_id} ma do niego klucz obcy (Claude.md §9).
	 */
	@Transactional
	void deleteCommunityData(UUID communityId) {
		postRepository.deleteByCommunityId(communityId);
	}

	private Page<PostResponse> mapToResponses(Page<Post> page, UUID viewerId) {
		Map<UUID, PostResponse> byId = toResponses(page.getContent(), viewerId).stream()
				.collect(Collectors.toMap(PostResponse::id, Function.identity()));
		return page.map(post -> byId.get(post.getId()));
	}

	@Transactional
	void likePost(UUID postId, UUID userId) {
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
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
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
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
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
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
	Page<CommentResponse> listComments(UUID postId, Pageable pageable) {
		requirePostExists(postId);
		Page<Comment> page = commentRepository.findByPostIdOrderByCreatedAtAsc(postId, pageable);
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
		postRepository.deleteByAuthorId(userId);
	}

	private void requirePostExists(UUID postId) {
		if (!postRepository.existsById(postId)) {
			throw new PostNotFoundException();
		}
	}

	private List<PostResponse> toResponses(List<Post> posts, UUID viewerId) {
		List<UUID> postIds = posts.stream().map(Post::getId).toList();
		if (postIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, Long> likeCounts = likeRepository.countByPostIdIn(postIds).stream().collect(
				Collectors.toMap(LikeRepository.PostIdCount::getPostId, LikeRepository.PostIdCount::getLikeCount));
		Map<UUID, Long> commentCounts = commentRepository.countByPostIdIn(postIds).stream().collect(Collectors
				.toMap(CommentRepository.PostIdCount::getPostId, CommentRepository.PostIdCount::getCommentCount));
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
						avatars.get(post.getAuthorId()), post.getContent(), post.getCreatedAt(), post.getCommunityId(),
						likeCounts.getOrDefault(post.getId(), 0L), likedByViewer.contains(post.getId()),
						commentCounts.getOrDefault(post.getId(), 0L), shareCounts.getOrDefault(post.getId(), 0L),
						sharedByViewer.contains(post.getId())))
				.collect(Collectors.toList());
	}
}
