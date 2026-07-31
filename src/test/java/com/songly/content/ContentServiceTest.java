package com.songly.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.songly.content.dto.CommentResponse;
import com.songly.content.dto.PostResponse;
import com.songly.common.storage.ObjectStorage;
import com.songly.community.CommunityMembership;
import com.songly.profile.ProfileDirectory;
import com.songly.social.SocialGraph;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

	@Mock
	private PostRepository postRepository;

	@Mock
	private LikeRepository likeRepository;

	@Mock
	private CommentRepository commentRepository;

	@Mock
	private ShareRepository shareRepository;

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private SocialGraph socialGraph;

	@Mock
	private CommunityMembership communityMembership;

	@Mock
	private ObjectStorage objectStorage;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private ContentService contentService;

	@BeforeEach
	void setUp() {
		contentService = new ContentService(postRepository, likeRepository, commentRepository, shareRepository,
				profileDirectory, socialGraph, communityMembership, objectStorage, eventPublisher);
	}

	@Test
	void createPost_savesAndReturnsResponseWithAuthorName() {
		UUID authorId = UUID.randomUUID();
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(authorId, "Ada"));

		PostResponse response = contentService.createPost(authorId, "Nowy album!", null);

		verify(postRepository).save(any(Post.class));
		assertThat(response.authorDisplayName()).isEqualTo("Ada");
		assertThat(response.content()).isEqualTo("Nowy album!");
		assertThat(response.likeCount()).isZero();
	}

	@Test
	void getPost_throws_whenMissing() {
		UUID postId = UUID.randomUUID();
		when(postRepository.findById(postId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> contentService.getPost(postId, UUID.randomUUID()))
				.isInstanceOf(PostNotFoundException.class);
	}

	@Test
	void deletePost_deletes_whenRequesterIsAuthor() {
		UUID authorId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), authorId, "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

		contentService.deletePost(post.getId(), authorId);

		verify(postRepository).delete(post);
	}

	@Test
	void deletePost_throws_whenRequesterIsNotAuthor() {
		Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

		assertThatThrownBy(() -> contentService.deletePost(post.getId(), UUID.randomUUID()))
				.isInstanceOf(NotResourceOwnerException.class);

		verify(postRepository, never()).delete(any(Post.class));
	}

	@Test
	void likePost_isIdempotent() {
		UUID userId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
		when(likeRepository.existsById(new LikeId(post.getId(), userId))).thenReturn(true);

		contentService.likePost(post.getId(), userId);

		verify(likeRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void likePost_publishesEventForPostAuthor() {
		UUID likerId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
		when(likeRepository.existsById(new LikeId(post.getId(), likerId))).thenReturn(false);

		contentService.likePost(post.getId(), likerId);

		verify(likeRepository).save(any(Like.class));
		verify(eventPublisher).publishEvent(any(PostLikedEvent.class));
	}

	@Test
	void likePost_throws_whenPostMissing() {
		UUID postId = UUID.randomUUID();
		when(postRepository.findById(postId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> contentService.likePost(postId, UUID.randomUUID()))
				.isInstanceOf(PostNotFoundException.class);
	}

	@Test
	void addComment_savesAndReturnsResponse_andPublishesEvent() {
		UUID authorId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(authorId, "Ada"));

		CommentResponse response = contentService.addComment(post.getId(), authorId, "Świetny kawałek");

		verify(commentRepository).save(any(Comment.class));
		verify(eventPublisher).publishEvent(any(PostCommentedEvent.class));
		assertThat(response.postId()).isEqualTo(post.getId());
		assertThat(response.authorDisplayName()).isEqualTo("Ada");
	}

	@Test
	void deleteComment_throws_whenCommentBelongsToDifferentPost() {
		UUID commentId = UUID.randomUUID();
		Comment comment = new Comment(commentId, UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

		assertThatThrownBy(() -> contentService.deleteComment(UUID.randomUUID(), commentId, comment.getAuthorId()))
				.isInstanceOf(CommentNotFoundException.class);
	}

	@Test
	void deleteComment_throws_whenRequesterIsNotAuthor() {
		UUID postId = UUID.randomUUID();
		UUID commentId = UUID.randomUUID();
		Comment comment = new Comment(commentId, postId, UUID.randomUUID(), "treść", Instant.now());
		when(commentRepository.findById(commentId)).thenReturn(Optional.of(comment));

		assertThatThrownBy(() -> contentService.deleteComment(postId, commentId, UUID.randomUUID()))
				.isInstanceOf(NotResourceOwnerException.class);

		verify(commentRepository, never()).delete(any(Comment.class));
	}

	@Test
	void feed_queriesPostsOfFriendsAndViewer() {
		UUID viewerId = UUID.randomUUID();
		UUID friendId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), friendId, "od znajomego", Instant.now());
		when(socialGraph.friendIds(viewerId)).thenReturn(Set.of(friendId));
		when(socialGraph.blockedEitherWayIds(viewerId)).thenReturn(Set.of());
		when(postRepository.findFeedPosts(any(), any(), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(post)));
		when(likeRepository.countByPostIdIn(any())).thenReturn(List.of());
		when(commentRepository.countByPostIdIn(any(), any())).thenReturn(List.of());
		when(shareRepository.countByPostIdIn(any())).thenReturn(List.of());
		when(likeRepository.findByPostIdInAndUserId(any(), any())).thenReturn(List.of());
		when(shareRepository.findByPostIdInAndUserId(any(), any())).thenReturn(List.of());
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(friendId, "Bono"));

		var page = contentService.feed(viewerId, org.springframework.data.domain.Pageable.unpaged());

		verify(postRepository).findFeedPosts(Set.of(friendId, viewerId), Set.of(),
				org.springframework.data.domain.Pageable.unpaged());
		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getContent().get(0).authorDisplayName()).isEqualTo("Bono");
	}

	@Test
	void sharePost_publishesEventOnlyForNewShare() {
		UUID sharerId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
		when(shareRepository.existsById(new ShareId(post.getId(), sharerId))).thenReturn(false);

		contentService.sharePost(post.getId(), sharerId);

		verify(shareRepository).save(any(Share.class));
		verify(eventPublisher).publishEvent(any(PostSharedEvent.class));
	}

	@Test
	void sharePost_isIdempotent() {
		UUID sharerId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), UUID.randomUUID(), "treść", Instant.now());
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));
		when(shareRepository.existsById(new ShareId(post.getId(), sharerId))).thenReturn(true);

		contentService.sharePost(post.getId(), sharerId);

		verify(shareRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void listComments_enrichesWithAuthorNames() {
		UUID postId = UUID.randomUUID();
		UUID authorId = UUID.randomUUID();
		Comment comment = new Comment(UUID.randomUUID(), postId, authorId, "treść", Instant.now());
		UUID viewerId = UUID.randomUUID();
		when(postRepository.findById(postId))
				.thenReturn(Optional.of(new Post(postId, authorId, "treść", Instant.now())));
		when(commentRepository.findByPostIdAndAuthorIdNotInOrderByCreatedAtAsc(any(), any(), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(comment)));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(authorId, "Ada"));

		var page = contentService.listComments(postId, viewerId, org.springframework.data.domain.Pageable.unpaged());

		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getContent().get(0).authorDisplayName()).isEqualTo("Ada");
	}

	/**
	 * Zapis na poście zablokowanego autora kończy się 404, a nie 403 — 403
	 * potwierdzałoby istnienie posta i fakt blokady.
	 */
	@Test
	void likePost_reportsPostOfBlockedAuthorAsMissing() {
		UUID viewerId = UUID.randomUUID();
		UUID blockedAuthorId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), blockedAuthorId, "treść", Instant.now());
		when(socialGraph.blockedEitherWayIds(viewerId)).thenReturn(Set.of(blockedAuthorId));
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

		assertThatThrownBy(() -> contentService.likePost(post.getId(), viewerId))
				.isInstanceOf(PostNotFoundException.class);

		verify(likeRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	/**
	 * Najważniejszy przypadek całej blokady: komentarz to jedyna ścieżka, którą
	 * zablokowany może napisać wprost do blokującego (przez powiadomienie o
	 * komentarzu pod jego postem).
	 */
	@Test
	void addComment_isRejectedUnderPostOfBlockedAuthor() {
		UUID commenterId = UUID.randomUUID();
		UUID blockedAuthorId = UUID.randomUUID();
		Post post = new Post(UUID.randomUUID(), blockedAuthorId, "treść", Instant.now());
		when(socialGraph.blockedEitherWayIds(commenterId)).thenReturn(Set.of(blockedAuthorId));
		when(postRepository.findById(post.getId())).thenReturn(Optional.of(post));

		assertThatThrownBy(() -> contentService.addComment(post.getId(), commenterId, "zaczepka"))
				.isInstanceOf(PostNotFoundException.class);

		verify(commentRepository, never()).save(any());
		verify(eventPublisher, never()).publishEvent(any());
	}

	/**
	 * Cofnięcie polubienia działa mimo blokady — inaczej blokujący zostałby na
	 * zawsze z polubieniem, którego nie może wycofać.
	 */
	@Test
	void unlikePost_worksDespiteBlock() {
		UUID viewerId = UUID.randomUUID();
		UUID postId = UUID.randomUUID();
		when(postRepository.existsById(postId)).thenReturn(true);

		contentService.unlikePost(postId, viewerId);

		verify(likeRepository).deleteById(new LikeId(postId, viewerId));
	}

	@Test
	void listPosts_returnsEmptyPageForTimelineOfBlockedAuthor() {
		UUID viewerId = UUID.randomUUID();
		UUID blockedAuthorId = UUID.randomUUID();
		when(socialGraph.blockedEitherWayIds(viewerId)).thenReturn(Set.of(blockedAuthorId));

		var page = contentService.listPosts(blockedAuthorId, viewerId,
				org.springframework.data.domain.Pageable.unpaged());

		assertThat(page).isEmpty();
		verify(postRepository, never()).findByAuthorIdAndCommunityIdIsNullOrderByCreatedAtDesc(any(), any());
	}

	@Test
	void feed_excludesPostsOfBlockedAuthors() {
		UUID viewerId = UUID.randomUUID();
		UUID friendId = UUID.randomUUID();
		UUID blockedId = UUID.randomUUID();
		when(socialGraph.friendIds(viewerId)).thenReturn(Set.of(friendId));
		when(socialGraph.blockedEitherWayIds(viewerId)).thenReturn(Set.of(blockedId));
		when(postRepository.findFeedPosts(any(), any(), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

		contentService.feed(viewerId, org.springframework.data.domain.Pageable.unpaged());

		verify(postRepository).findFeedPosts(Set.of(friendId, viewerId), Set.of(blockedId),
				org.springframework.data.domain.Pageable.unpaged());
	}
}
