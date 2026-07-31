package com.songly.content;

import com.songly.content.dto.CommentResponse;
import com.songly.content.dto.CreateCommentRequest;
import com.songly.common.media.EmptyUploadException;
import com.songly.content.dto.CreatePostRequest;
import com.songly.content.dto.PostImageResponse;
import com.songly.content.dto.PostResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/posts")
@SecurityRequirement(name = "bearerAuth")
class PostController {

	private final ContentService contentService;

	PostController(ContentService contentService) {
		this.contentService = contentService;
	}

	@PostMapping
	ResponseEntity<PostResponse> createPost(Authentication authentication,
			@Valid @RequestBody CreatePostRequest request) {
		PostResponse response = contentService.createPost(currentUserId(authentication), request.content(),
				request.imageKey());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * Wgranie zdjęcia jest osobnym krokiem od publikacji posta (patrz
	 * {@code CreatePostRequest}). Zwrócony klucz podaje się potem w
	 * {@code imageKey}; sam w sobie nie tworzy jeszcze żadnej treści.
	 */
	@PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	PostImageResponse uploadPostImage(Authentication authentication, @RequestPart("file") MultipartFile file)
			throws IOException {
		if (file.isEmpty()) {
			throw new EmptyUploadException();
		}
		return contentService.uploadPostImage(currentUserId(authentication), file.getBytes());
	}

	@GetMapping
	Page<PostResponse> listPosts(Authentication authentication, @RequestParam(required = false) UUID authorId,
			@PageableDefault(size = 20) Pageable pageable) {
		return contentService.listPosts(authorId, currentUserId(authentication), pageable);
	}

	@GetMapping("/{postId}")
	PostResponse getPost(Authentication authentication, @PathVariable UUID postId) {
		return contentService.getPost(postId, currentUserId(authentication));
	}

	@DeleteMapping("/{postId}")
	ResponseEntity<Void> deletePost(Authentication authentication, @PathVariable UUID postId) {
		contentService.deletePost(postId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{postId}/likes")
	ResponseEntity<Void> likePost(Authentication authentication, @PathVariable UUID postId) {
		contentService.likePost(postId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{postId}/likes")
	ResponseEntity<Void> unlikePost(Authentication authentication, @PathVariable UUID postId) {
		contentService.unlikePost(postId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{postId}/shares")
	ResponseEntity<Void> sharePost(Authentication authentication, @PathVariable UUID postId) {
		contentService.sharePost(postId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/{postId}/shares")
	ResponseEntity<Void> unsharePost(Authentication authentication, @PathVariable UUID postId) {
		contentService.unsharePost(postId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{postId}/comments")
	ResponseEntity<CommentResponse> addComment(Authentication authentication, @PathVariable UUID postId,
			@Valid @RequestBody CreateCommentRequest request) {
		CommentResponse response = contentService.addComment(postId, currentUserId(authentication), request.content());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@GetMapping("/{postId}/comments")
	Page<CommentResponse> listComments(Authentication authentication, @PathVariable UUID postId,
			@PageableDefault(size = 20) Pageable pageable) {
		return contentService.listComments(postId, currentUserId(authentication), pageable);
	}

	@DeleteMapping("/{postId}/comments/{commentId}")
	ResponseEntity<Void> deleteComment(Authentication authentication, @PathVariable UUID postId,
			@PathVariable UUID commentId) {
		contentService.deleteComment(postId, commentId, currentUserId(authentication));
		return ResponseEntity.noContent().build();
	}

	private UUID currentUserId(Authentication authentication) {
		return (UUID) authentication.getPrincipal();
	}
}
