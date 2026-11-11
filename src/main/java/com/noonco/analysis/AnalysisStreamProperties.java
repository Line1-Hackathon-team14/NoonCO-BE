package com.noonco.analysis;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("analysis.stream")
public record AnalysisStreamProperties(
		// 동시에 실행할 수 있는 해설 스트림 수. 넘으면 AI_BUSY
		@DefaultValue("3") int maxConcurrent,
		// 연결 유지·끊김 감지를 위한 ping 전송 간격
		@DefaultValue("3s") Duration pingInterval
) {
}
