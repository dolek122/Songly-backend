package com.songly.analytics;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, UUID> {

	@Query("select a.type as type, count(a) as count from AnalyticsEvent a where a.occurredAt >= :since group by a.type")
	List<TypeCount> countByTypeSince(@Param("since") Instant since);

	interface TypeCount {

		AnalyticsEventType getType();

		long getCount();
	}
}
