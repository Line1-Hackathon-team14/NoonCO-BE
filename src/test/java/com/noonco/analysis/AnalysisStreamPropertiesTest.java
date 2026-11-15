package com.noonco.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AnalysisStreamPropertiesTest {

	@Test
	void rejectsNonPositiveMaxConcurrent() {
		assertThatThrownBy(() -> new AnalysisStreamProperties(0, Duration.ofSeconds(3), 350))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(-1, Duration.ofSeconds(3), 350))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositivePingInterval() {
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, Duration.ZERO, 350))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, Duration.ofSeconds(-1), 350))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, null, 350))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNonPositiveSummaryMaxLength() {
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, Duration.ofSeconds(3), 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AnalysisStreamProperties(3, Duration.ofSeconds(3), -1))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsPositiveValues() {
		assertThatCode(() -> new AnalysisStreamProperties(1, Duration.ofMillis(1), 1)).doesNotThrowAnyException();
	}

	@Test
	void summaryMaxLengthDefaultsTo350AndBindsFromProperty() {
		assertThat(bind(Map.of()).summaryMaxLength()).isEqualTo(350);
		assertThat(bind(Map.of("analysis.stream.summary-max-length", "50")).summaryMaxLength()).isEqualTo(50);
	}

	@Test
	void bindingNonPositiveSummaryMaxLengthFails() {
		assertThatThrownBy(() -> bind(Map.of("analysis.stream.summary-max-length", "0")))
				.isInstanceOf(BindException.class)
				.rootCause().isInstanceOf(IllegalArgumentException.class);
	}

	private static AnalysisStreamProperties bind(Map<String, String> properties) {
		return new Binder(new MapConfigurationPropertySource(properties))
				.bindOrCreate("analysis.stream", AnalysisStreamProperties.class);
	}
}
