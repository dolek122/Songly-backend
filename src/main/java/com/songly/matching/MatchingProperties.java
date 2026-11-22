package com.songly.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "songly.matching")
class MatchingProperties {

	private double confidencePrior = 2.0;

	double getConfidencePrior() {
		return confidencePrior;
	}

	void setConfidencePrior(double confidencePrior) {
		this.confidencePrior = confidencePrior;
	}
}
