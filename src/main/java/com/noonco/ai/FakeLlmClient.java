package com.noonco.ai;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.noonco.global.file.FileType;

/**
 * Gemini 를 호출하지 않는 테스트·개발용 구현.
 * 첫 줄은 메타 JSON 이고, 일부러 여러 조각으로 쪼개서 내보낸다.
 * 이미지 요청이면 {"visualType":"LINE","title":"..."}, PDF 요청(mimeType application/pdf)이면 {"title":"..."} 이다.
 * 둘째 줄부터는 해설 본문이다. 이미지 응답은 구분선 줄({@value LlmOutputParser#DETAIL_SEPARATOR}) 뒤에 축·수치 상세 해설이 붙고,
 * 구분선은 조각 경계에 걸치게 나눠 둔다. PDF 응답은 상세 없이 요약만 있다.
 */
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "fake")
public class FakeLlmClient implements LlmClient {

	static final String MODEL = "fake";

	static final List<String> CHUNKS = List.of(
			"{\"visualType\":\"LI",
			"NE\",\"title\":\"월별 ",
			"방문자 수 추이\"}",
			"\n",
			"이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.\n",
			"가로축은 월, 세로축은 방문자 수이며, ",
			"전체적으로 꾸준히 증가하는 흐름을 보입니다.\n",
			"가장 낮은 달은 1월로 약 1,200명이고, ",
			"가장 높은 달은 6월로 약 3,400명입니다.\n@@DE",
			"TAIL@@\n가로축은 1월부터 6월까지 한 달 간격이고, ",
			"세로축은 방문자 수(명)로 0명부터 4,000명까지 1,000명 간격의 눈금이 있습니다.\n",
			"최솟값은 1월의 약 1,200명, 최댓값은 6월의 약 3,400명입니다.\n",
			"2월 약 1,500명, 3월 약 1,900명, 4월 약 2,300명, 5월 약 2,800명으로 매달 늘어, ",
			"6개월 동안 약 2.8배가 되었습니다.\n"
	);

	// 이미지 응답과 메타 줄이 다르고, 상세 해설이 없다
	static final List<String> PDF_CHUNKS = List.of(
			"{\"title\":\"월별 ",
			"방문자 수 보고서\"}",
			"\n",
			"이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.\n",
			"가로축은 월, 세로축은 방문자 수이며, ",
			"전체적으로 꾸준히 증가하는 흐름을 보입니다.\n",
			"가장 낮은 달은 1월로 약 1,200명이고, ",
			"가장 높은 달은 6월로 약 3,400명입니다.\n"
	);

	private final Duration chunkDelay;

	public FakeLlmClient(LlmProperties properties) {
		this.chunkDelay = properties.fake().chunkDelay();
	}

	@Override
	public LlmStream stream(LlmRequest request) {
		List<String> chunks = isPdf(request) ? PDF_CHUNKS : CHUNKS;
		return new FakeLlmStream(chunks.iterator(), chunkDelay);
	}

	@Override
	public String model() {
		return MODEL;
	}

	private static boolean isPdf(LlmRequest request) {
		return switch (request.input()) {
			case LlmInput.File file -> FileType.PDF.getMimeType().equals(file.mimeType());
		};
	}

	private static class FakeLlmStream extends AbstractLlmStream {

		private final Iterator<String> chunks;
		private final Duration delay;
		private final CountDownLatch closeSignal = new CountDownLatch(1);

		FakeLlmStream(Iterator<String> chunks, Duration delay) {
			this.chunks = chunks;
			this.delay = delay;
		}

		@Override
		protected String fetchNext() {
			if (!chunks.hasNext()) {
				return null;
			}
			try {
				// 조각 사이 지연을 흉내 낸다. 대기 중 close 되면 즉시 끝낸다.
				if (closeSignal.await(delay.toNanos(), TimeUnit.NANOSECONDS)) {
					return null;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			return chunks.next();
		}

		@Override
		protected void onClose() {
			closeSignal.countDown();
		}

		@Override
		public String model() {
			return MODEL;
		}
	}
}
