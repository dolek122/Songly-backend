package com.songly.analytics;

import com.songly.analytics.dto.AnalyticsSummaryResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@SecurityRequirement(name = "bearerAuth")
class AnalyticsController {

	private final AnalyticsService analyticsService;

	AnalyticsController(AnalyticsService analyticsService) {
		this.analyticsService = analyticsService;
	}

	@GetMapping("/summary")
	AnalyticsSummaryResponse summary(@RequestParam(defaultValue = "7") int days) {
		return analyticsService.summary(days);
	}
}
