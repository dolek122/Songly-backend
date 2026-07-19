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

@ExtendWith(MockitoExtension.class)
class ContentServiceTest {

	@Mock
	private PostRepository postRepository;

	@Mock
	private LikeRepository likeRepository;

	@Mock
	private CommentRepository commentRepository;

	@Mock
	private ProfileDirectory profileDirectory;

	@Mock
	private SocialGraph socialGraph;

	private ContentService contentService;

	@BeforeEach
	void setUp() {
		contentService = new ContentService(postRepository, likeRepository, commentRepository, profileDirectory,
				socialGraph);
	}

	@Test
	void createPost_savesAndReturnsResponseWithAuthorName() {
		UUID authorId = UUID.randomUUID();
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(authorId, "Ada"));

		PostResponse response = contentService.createPost(authorId, "Nowy album!");

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
		UUID postId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		when(postRepository.existsById(postId)).thenReturn(true);
		when(likeRepository.existsById(new LikeId(postId, userId))).thenReturn(true);

		contentService.likePost(postId, userId);

		verify(likeRepository, never()).save(any());
	}

	@Test
	void likePost_throws_whenPostMissing() {
		UUID postId = UUID.randomUUID();
		when(postRepository.existsById(postId)).thenReturn(false);

		assertThatThrownBy(() -> contentService.likePost(postId, UUID.randomUUID()))
				.isInstanceOf(PostNotFoundException.class);
	}

	@Test
	void addComment_savesAndReturnsResponse() {
		UUID postId = UUID.randomUUID();
		UUID authorId = UUID.randomUUID();
		when(postRepository.existsById(postId)).thenReturn(true);
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(authorId, "Ada"));

		CommentResponse response = contentService.addComment(postId, authorId, "Świetny kawałek");

		verify(commentRepository).save(any(Comment.class));
		assertThat(response.postId()).isEqualTo(postId);
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
		when(postRepository.findByAuthorIdInOrderByCreatedAtDesc(any(), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(post)));
		when(likeRepository.countByPostIdIn(any())).thenReturn(List.of());
		when(commentRepository.countByPostIdIn(any())).thenReturn(List.of());
		when(likeRepository.findByPostIdInAndUserId(any(), any())).thenReturn(List.of());
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(friendId, "Bono"));

		var page = contentService.feed(viewerId, org.springframework.data.domain.Pageable.unpaged());

		verify(postRepository).findByAuthorIdInOrderByCreatedAtDesc(Set.of(friendId, viewerId),
				org.springframework.data.domain.Pageable.unpaged());
		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getContent().get(0).authorDisplayName()).isEqualTo("Bono");
	}

	@Test
	void listComments_enrichesWithAuthorNames() {
		UUID postId = UUID.randomUUID();
		UUID authorId = UUID.randomUUID();
		Comment comment = new Comment(UUID.randomUUID(), postId, authorId, "treść", Instant.now());
		when(postRepository.existsById(postId)).thenReturn(true);
		when(commentRepository.findByPostIdOrderByCreatedAtAsc(any(), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(comment)));
		when(profileDirectory.displayNames(anyCollection())).thenReturn(Map.of(authorId, "Ada"));

		var page = contentService.listComments(postId, org.springframework.data.domain.Pageable.unpaged());

		assertThat(page.getContent()).hasSize(1);
		assertThat(page.getContent().get(0).authorDisplayName()).isEqualTo("Ada");
	}
}
