package com.songly.content;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PostReportRepository extends JpaRepository<PostReport, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from PostReport r where r.id = :id")
	Optional<PostReport> findByIdForUpdate(@Param("id") UUID id);

	boolean existsByPostIdAndReporterId(UUID postId, UUID reporterId);

	boolean existsByPostIdAndStatus(UUID postId, ReportStatus status);

	long countByPostId(UUID postId);

	@Query("select r.reporterId from PostReport r where r.postId = :postId")
	List<UUID> findReporterIdsByPostId(@Param("postId") UUID postId);

	long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant createdAfter);

	long countByReporterIdAndStatus(UUID reporterId, ReportStatus status);

	@Query("""
			select r.reporterId as reporterId, count(r) as dismissedCount from PostReport r
			join Post p on p.id = r.postId
			where p.communityId = :communityId and r.status = :status and r.reporterId in :reporterIds
			group by r.reporterId
			""")
	List<ReporterDismissedCount> countDismissedByReporterIdIn(@Param("communityId") UUID communityId,
			@Param("reporterIds") Collection<UUID> reporterIds, @Param("status") ReportStatus status);

	@Query("""
			select r from PostReport r join Post p on p.id = r.postId
			where p.communityId = :communityId and r.status = :status
			order by r.createdAt asc
			""")
	Page<PostReport> findByCommunityIdAndStatus(@Param("communityId") UUID communityId,
			@Param("status") ReportStatus status, Pageable pageable);

	@Query("""
			select r from PostReport r join Post p on p.id = r.postId
			where p.communityId is null and r.status = :status
			order by r.createdAt asc
			""")
	Page<PostReport> findOutsideCommunitiesByStatus(@Param("status") ReportStatus status, Pageable pageable);

	@Query("""
			select r.postId as postId, count(r) as reportCount from PostReport r
			where r.postId in :postIds and r.status = :status group by r.postId
			""")
	java.util.List<PostIdCount> countByPostIdIn(@Param("postIds") java.util.Collection<UUID> postIds,
			@Param("status") ReportStatus status);

	@Modifying(flushAutomatically = true)
	@Query("delete from PostReport r where r.reporterId = :reporterId")
	void deleteByReporterId(@Param("reporterId") UUID reporterId);

	@Modifying(flushAutomatically = true)
	@Query("update PostReport r set r.reviewedBy = null where r.reviewedBy = :reviewerId")
	void clearReviewer(@Param("reviewerId") UUID reviewerId);

	interface PostIdCount {

		UUID getPostId();

		long getReportCount();
	}

	interface ReporterDismissedCount {

		UUID getReporterId();

		long getDismissedCount();
	}
}
