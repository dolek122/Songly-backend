package com.songly.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

	void dismiss(UUID reviewerId, Instant now) {
		this.status = ReportStatus.DISMISSED;
		this.reviewedBy = reviewerId;
		this.reviewedAt = now;
	}

}
