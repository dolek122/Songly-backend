package com.songly.content;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface PostImageUploadRepository extends JpaRepository<PostImageUpload, String> {

	@Query("select u.imageKey from PostImageUpload u where u.ownerId = :ownerId order by u.createdAt desc")
	List<String> findKeysByOwnerIdNewestFirst(@Param("ownerId") UUID ownerId);

	@Query("select u.imageKey from PostImageUpload u where u.createdAt < :cutoff order by u.createdAt")
	List<String> findKeysCreatedBefore(@Param("cutoff") Instant cutoff, Limit limit);

	@Modifying(flushAutomatically = true)
	@Transactional
	@Query("delete from PostImageUpload u where u.imageKey = :imageKey and u.ownerId = :ownerId")
	int deleteByImageKeyAndOwnerId(@Param("imageKey") String imageKey, @Param("ownerId") UUID ownerId);

	@Modifying(flushAutomatically = true)
	@Transactional
	@Query("delete from PostImageUpload u where u.imageKey = :imageKey")
	int deleteByImageKey(@Param("imageKey") String imageKey);

	@Modifying
	@Query("delete from PostImageUpload u where u.ownerId = :ownerId")
	void deleteByOwnerId(@Param("ownerId") UUID ownerId);
}
