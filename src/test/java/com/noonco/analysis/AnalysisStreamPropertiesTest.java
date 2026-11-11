package com.noonco.analysis;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AnalysisStreamPropertiesTest {

	@Test
	void rejectsNonPositiveMaxConcurrent() {
		assertThatThrownBy(() -> new AnalysisStreamProperties(0, Duration.ofSeconds(3)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(-1, Duration.ofSeconds(3)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositivePingInterval() {
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, Duration.ofSeconds(-1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsPositiveValues() {
		assertThatCode(() -> new AnalysisStreamProperties(1, Duration.ofMillis(1))).doesNotThrowAnyException();
	}
}
