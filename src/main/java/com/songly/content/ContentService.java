package com.songly.content;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ContentService {

	private final PostRepository postRepository;
	private final LikeRepository likeRepository;
	private final CommentRepository commentRepository;
	private final ProfileDirectory profileDirectory;
	private final SocialGraph socialGraph;

	ContentService(PostRepository postRepository, LikeRepository likeRepository, CommentRepository commentRepository,
			ProfileDirectory profileDirectory, SocialGraph socialGraph) {
		this.postRepository = postRepository;
		this.likeRepository = likeRepository;
		this.commentRepository = commentRepository;
		this.profileDirectory = profileDirectory;
		this.socialGraph = socialGraph;
	}

	@Transactional
	PostResponse createPost(UUID authorId, String content) {
		Post post = new Post(UUID.randomUUID(), authorId, content, Instant.now());
		postRepository.save(post);
		String displayName = profileDirectory.displayNames(Set.of(authorId)).get(authorId);
		return new PostResponse(post.getId(), authorId, displayName, post.getContent(), post.getCreatedAt(), 0, false,
				0);
	}

	@Transactional(readOnly = true)
	PostResponse getPost(UUID postId, UUID viewerId) {
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
		return toResponses(List.of(post), viewerId).get(0);
	}

	@Transactional(readOnly = true)
	Page<PostResponse> listPosts(UUID authorId, UUID viewerId, Pageable pageable) {
		Page<Post> page = authorId == null
				? postRepository.findAllByOrderByCreatedAtDesc(pageable)
				: postRepository.findByAuthorIdOrderByCreatedAtDesc(authorId, pageable);
		Map<UUID, PostResponse> byId = toResponses(page.getContent(), viewerId).stream()
				.collect(Collectors.toMap(PostResponse::id, Function.identity()));
		return page.map(post -> byId.get(post.getId()));
	}

	/**
	 * Prosty feed MVP: posty znajomych i własne, chronologicznie. Zapytanie po
	 * liście autorów wystarczy przy setkach znajomych; przy realnym ruchu to
	 * pierwszy kandydat na read-replikę / precomputed feed (Claude.md §10).
	 */
	@Transactional(readOnly = true)
	Page<PostResponse> feed(UUID viewerId, Pageable pageable) {
		Set<UUID> authorIds = new HashSet<>(socialGraph.friendIds(viewerId));
		authorIds.add(viewerId);
		Page<Post> page = postRepository.findByAuthorIdInOrderByCreatedAtDesc(authorIds, pageable);
		Map<UUID, PostResponse> byId = toResponses(page.getContent(), viewerId).stream()
				.collect(Collectors.toMap(PostResponse::id, Function.identity()));
		return page.map(post -> byId.get(post.getId()));
	}

	@Transactional
	void deletePost(UUID postId, UUID requesterId) {
		Post post = postRepository.findById(postId).orElseThrow(PostNotFoundException::new);
		if (!post.getAuthorId().equals(requesterId)) {
			throw new NotResourceOwnerException();
		}
		postRepository.delete(post);
	}

	@Transactional
	void likePost(UUID postId, UUID userId) {
		requirePostExists(postId);
		if (!likeRepository.existsById(new LikeId(postId, userId))) {
			likeRepository.save(new Like(postId, userId, Instant.now()));
		}
	}

	@Transactional
	void unlikePost(UUID postId, UUID userId) {
		requirePostExists(postId);
		likeRepository.deleteById(new LikeId(postId, userId));
	}

	@Transactional
	CommentResponse addComment(UUID postId, UUID authorId, String content) {
		requirePostExists(postId);
		Comment comment = new Comment(UUID.randomUUID(), postId, authorId, content, Instant.now());
		commentRepository.save(comment);
		String displayName = profileDirectory.displayNames(Set.of(authorId)).get(authorId);
		return new CommentResponse(comment.getId(), postId, authorId, displayName, comment.getContent(),
				comment.getCreatedAt());
	}

	@Transactional(readOnly = true)
	Page<CommentResponse> listComments(UUID postId, Pageable pageable) {
		requirePostExists(postId);
		Page<Comment> page = commentRepository.findByPostIdOrderByCreatedAtAsc(postId, pageable);
		Map<UUID, String> names = profileDirectory
				.displayNames(page.getContent().stream().map(Comment::getAuthorId).collect(Collectors.toSet()));
		return page.map(comment -> new CommentResponse(comment.getId(), comment.getPostId(), comment.getAuthorId(),
				names.get(comment.getAuthorId()), comment.getContent(), comment.getCreatedAt()));
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
		Set<UUID> likedByViewer = likeRepository.findByPostIdInAndUserId(postIds, viewerId).stream()
				.map(Like::getPostId).collect(Collectors.toSet());
		Map<UUID, String> names = profileDirectory
				.displayNames(posts.stream().map(Post::getAuthorId).collect(Collectors.toSet()));
		return posts.stream()
				.map(post -> new PostResponse(post.getId(), post.getAuthorId(), names.get(post.getAuthorId()),
						post.getContent(), post.getCreatedAt(), likeCounts.getOrDefault(post.getId(), 0L),
						likedByViewer.contains(post.getId()), commentCounts.getOrDefault(post.getId(), 0L)))
				.collect(Collectors.toList());
	}
}
