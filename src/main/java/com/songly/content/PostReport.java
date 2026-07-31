package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Zgłoszenie posta do moderacji.
 *
 * <p>
 * Tabela należy do modułu content, bo to on jest właścicielem postów i tylko on
 * może mieć do nich klucz obcy (Claude.md §2). Uprawnienie do oglądania kolejki
 * sprawdzamy przez publiczny interfejs modułu community, a nie zaglądając w
 * jego tabele.
 */
@Entity
@Table(name = "post_reports")
class PostReport {

	@Id
	private UUID id;

	@Column(name = "post_id", nullable = false)
	private UUID postId;

	@Column(name = "reporter_id", nullable = false)
	private UUID reporterId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private ReportReason reason;

	@Column(length = 500)
	private String details;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReportStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "reviewed_by")
	private UUID reviewedBy;

	protected PostReport() {
	}

	PostReport(UUID id, UUID postId, UUID reporterId, ReportReason reason, String details, Instant createdAt) {
		this.id = id;
		this.postId = postId;
		this.reporterId = reporterId;
		this.reason = reason;
		this.details = details;
		this.status = ReportStatus.PENDING;
		this.createdAt = createdAt;
	}

	UUID getId() {
		return id;
	}

	UUID getPostId() {
		return postId;
	}

	UUID getReporterId() {
		return reporterId;
	}

	ReportReason getReason() {
		return reason;
	}

	String getDetails() {
		return details;
	}

	ReportStatus getStatus() {
		return status;
	}

	Instant getCreatedAt() {
		return createdAt;
	}

	/**
	 * Zgłoszenie odrzucone — moderator uznał, że post zostaje.
	 *
	 * <p>
	 * Nie ma tu odpowiednika „uwzględnione": skasowanie posta zabiera jego
	 * zgłoszenia kaskadą, bo bez posta nie ma czego moderować. Świadomy koszt:
	 * kolejka nie jest archiwum decyzji. Gdy będzie potrzebny ślad audytowy, trzeba
	 * będzie zdjąć kaskadę i zacząć przechowywać kopię treści — czyli osobna
	 * decyzja, nie rozszerzenie tej.
	 */
	void dismiss(UUID reviewerId, Instant now) {
		this.status = ReportStatus.DISMISSED;
		this.reviewedBy = reviewerId;
		this.reviewedAt = now;
	}

}
