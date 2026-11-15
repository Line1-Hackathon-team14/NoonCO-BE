package com.noonco.analysis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("analysis.stream")
public record AnalysisStreamProperties(
		// 동시에 실행할 수 있는 해설 스트림 수. 넘으면 AI_BUSY
		@DefaultValue("3") int maxConcurrent,
		// 연결 유지·끊김 감지를 위한 ping 전송 간격
		@DefaultValue("3s") Duration pingInterval,
		// 이미지 요약 해설 최대 길이(공백 포함 Unicode code point 수). 넘으면 문장 경계에서 자른다. PDF 에는 적용하지 않는다
		@DefaultValue("350") int summaryMaxLength
) {

	/**
	 * 잘못된 값이면 기동 시점에 실패시킨다. 0 이하 동시 실행 수는 모든 요청을 AI_BUSY 로, 0 이하 ping 간격은 ping 폭주를 만들고,
	 * 0 이하 요약 길이는 요약을 전부 버린다.
	 */
	public AnalysisStreamProperties {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException("analysis.stream.max-concurrent 는 1 이상이어야 합니다: " + maxConcurrent);
		}
		if (pingInterval == null || pingInterval.isZero() || pingInterval.isNegative()) {
			throw new IllegalArgumentException("analysis.stream.ping-interval 은 0보다 커야 합니다: " + pingInterval);
		}
		if (summaryMaxLength < 1) {
			throw new IllegalArgumentException("analysis.stream.summary-max-length 는 1 이상이어야 합니다: " + summaryMaxLength);
		}
	}
}
