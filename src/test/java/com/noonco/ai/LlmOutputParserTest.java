package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.noonco.analysis.entity.VisualType;
import com.noonco.material.entity.SourceType;

class LlmOutputParserTest {

	// 기본 설정값(analysis.stream.summary-max-length)
	private static final int SUMMARY_MAX_LENGTH = 350;
	private static final String FAKE_OUTPUT = String.join("", FakeLlmClient.CHUNKS);
	private static final String FAKE_SUMMARY = """
			이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.
			가로축은 월, 세로축은 방문자 수이며, 전체적으로 꾸준히 증가하는 흐름을 보입니다.
			가장 낮은 달은 1월로 약 1,200명이고, 가장 높은 달은 6월로 약 3,400명입니다.""";
	private static final String FAKE_DETAIL = """
			가로축은 1월부터 6월까지 한 달 간격이고, 세로축은 방문자 수(명)로 0명부터 4,000명까지 1,000명 간격의 눈금이 있습니다.
			최솟값은 1월의 약 1,200명, 최댓값은 6월의 약 3,400명입니다.
			2월 약 1,500명, 3월 약 1,900명, 4월 약 2,300명, 5월 약 2,800명으로 매달 늘어, 6개월 동안 약 2.8배가 되었습니다.
			""";
	private static final LlmOutputEvent.Meta FAKE_META = new LlmOutputEvent.Meta(VisualType.LINE, "월별 방문자 수 추이");

	// 메타 뒤 빈 줄(제외 대상)과 본문 중간 빈 줄(그대로 전달)을 함께 담는다.
	private static final String IMAGE_OUTPUT = "{\"visualType\":\"bar\",\"title\":\"지역별 매출\"}\n\n서울이 가장 높습니다.\n\n부산이 뒤를 잇습니다.";
	private static final String IMAGE_BODY = "서울이 가장 높습니다.\n\n부산이 뒤를 잇습니다.";
	private static final String IMAGE_CRLF_OUTPUT = "{\"visualType\":\"PIE\",\"title\":\"점유율\"}\r\n\r\nA사가 절반입니다.\r\n\r\nB사가 나머지입니다.";
	private static final String IMAGE_CRLF_BODY = "A사가 절반입니다.\r\n\r\nB사가 나머지입니다.";
	private static final String LEADING_BLANK_OUTPUT = "\n \r\n\t\n{\"visualType\":\"OTHER\",\"title\":\"기타 자료\"}\n본문입니다.";
	private static final String PDF_OUTPUT = "{\"title\":\"회의록 요약\"}\n회의에서는 예산을 논의했습니다.";
	private static final String UNREADABLE_OUTPUT = "{\"unreadable\":true}\n무시되어야 하는 텍스트";
	// 구분선 앞뒤 빈 줄(제외 대상), 두 번째 구분선(상세의 일반 텍스트), 구분선처럼 보이지만 아닌 줄을 함께 담는다.
	private static final String SEPARATOR_OUTPUT = "{\"visualType\":\"BAR\",\"title\":\"t\"}\n요약입니다.\n@@DETAIL@@x\n"
			+ "@@DETAIL\n끝.\n\n\n  @@DETAIL@@ \n\n\n상세입니다.\n@@DETAIL@@\n상세 끝.\n";
	private static final String SEPARATOR_SUMMARY = "요약입니다.\n@@DETAIL@@x\n@@DETAIL\n끝.";
	private static final String SEPARATOR_DETAIL = "상세입니다.\n@@DETAIL@@\n상세 끝.\n";
	private static final String SEPARATOR_CRLF_OUTPUT = "{\"visualType\":\"PIE\",\"title\":\"t\"}\r\n요약입니다.\r\n\r\n@@DETAIL@@\r\n\r\n상세입니다.\r\n";
	private static final String PDF_SEPARATOR_OUTPUT = "{\"title\":\"t\"}\n요약 줄\n@@DE 아님\n@@DETAIL@@\n상세 줄";
	// 350자를 넘는 이미지 요약(이모지 포함)은 문장 경계에서 잘리고, 상세는 그대로다.
	private static final String LONG_SUMMARY_FIRST = "😀".repeat(200) + "첫 문장입니다.";
	private static final String LONG_SUMMARY_OUTPUT = "{\"visualType\":\"LINE\",\"title\":\"t\"}\n" + LONG_SUMMARY_FIRST
			+ " " + "둘째 문장".repeat(40) + "입니다.\n@@DETAIL@@\n상세는 자르지 않습니다." + "가".repeat(400);

	/**
	 * 파싱 결과를 비교하기 쉬운 형태로 정리한 것. Delta 는 구간별로 이어 붙인다.
	 */
	private record Result(List<LlmOutputEvent> heads, String summary, String detail) {
	}

	private static Result parse(SourceType sourceType, List<String> chunks) {
		return parse(sourceType, SUMMARY_MAX_LENGTH, chunks);
	}

	private static Result parse(SourceType sourceType, int summaryMaxLength, List<String> chunks) {
		LlmOutputParser parser = new LlmOutputParser(sourceType, summaryMaxLength);
		List<LlmOutputEvent> events = new ArrayList<>();
		chunks.forEach(chunk -> events.addAll(parser.feed(chunk)));
		events.addAll(parser.finish());

		List<LlmOutputEvent> heads = new ArrayList<>();
		StringBuilder summary = new StringBuilder();
		StringBuilder detail = new StringBuilder();
		for (LlmOutputEvent event : events) {
			if (event instanceof LlmOutputEvent.Delta delta) {
				assertThat(delta.text()).isNotEmpty();
				if (delta.section() == LlmOutputEvent.Section.SUMMARY) {
					assertThat(detail).as("요약 Delta 는 상세 Delta 보다 먼저 나와야 한다").isEmpty();
					summary.append(delta.text());
				} else {
					detail.append(delta.text());
				}
			} else {
				assertThat(summary).as("Meta/Unreadable 는 Delta 보다 먼저 나와야 한다").isEmpty();
				assertThat(detail).as("Meta/Unreadable 는 Delta 보다 먼저 나와야 한다").isEmpty();
				heads.add(event);
			}
		}
		return new Result(heads, summary.toString(), detail.toString());
	}

	private static List<String> oneCharChunks(String output) {
		return output.chars().mapToObj(c -> String.valueOf((char)c)).toList();
	}

	static Stream<Arguments> allSplits() {
		return Stream.of(
				Arguments.of(SourceType.IMAGE, FAKE_OUTPUT),
				Arguments.of(SourceType.IMAGE, IMAGE_OUTPUT),
				Arguments.of(SourceType.IMAGE, IMAGE_CRLF_OUTPUT),
				Arguments.of(SourceType.IMAGE, LEADING_BLANK_OUTPUT),
				Arguments.of(SourceType.IMAGE, UNREADABLE_OUTPUT),
				Arguments.of(SourceType.PDF, PDF_OUTPUT),
				Arguments.of(SourceType.IMAGE, SEPARATOR_OUTPUT),
				Arguments.of(SourceType.IMAGE, SEPARATOR_CRLF_OUTPUT),
				Arguments.of(SourceType.PDF, PDF_SEPARATOR_OUTPUT),
				Arguments.of(SourceType.IMAGE, LONG_SUMMARY_OUTPUT)
		).flatMap(args -> {
			String output = (String)args.get()[1];
			return IntStream.rangeClosed(0, output.length())
					.mapToObj(at -> Arguments.of(args.get()[0], output, at));
		});
	}

	@ParameterizedTest(name = "{0} 출력을 {2} 위치에서 두 조각으로 분할")
	@MethodSource("allSplits")
	void sameResultRegardlessOfSplitPosition(SourceType sourceType, String output, int at) {
		Result whole = parse(sourceType, List.of(output));
		Result split = parse(sourceType, List.of(output.substring(0, at), output.substring(at)));
		Result oneChar = parse(sourceType, oneCharChunks(output));

		assertThat(split).isEqualTo(whole);
		assertThat(oneChar).isEqualTo(whole);
	}

	private static LlmOutputEvent.Delta summary(String text) {
		return new LlmOutputEvent.Delta(LlmOutputEvent.Section.SUMMARY, text);
	}

	private static LlmOutputEvent.Delta detail(String text) {
		return new LlmOutputEvent.Delta(LlmOutputEvent.Section.DETAIL, text);
	}

	@Test
	void fakeClientOutputGivesLineMetaSummaryAndDetail() {
		Result result = parse(SourceType.IMAGE, FakeLlmClient.CHUNKS);

		assertThat(result.heads()).containsExactly(FAKE_META);
		assertThat(result.summary()).isEqualTo(FAKE_SUMMARY);
		assertThat(result.detail()).isEqualTo(FAKE_DETAIL);
	}

	@Test
	void fakeClientPdfOutputHasNoDetail() {
		Result result = parse(SourceType.PDF, FakeLlmClient.PDF_CHUNKS);

		assertThat(result.summary()).isEqualTo(FAKE_SUMMARY + "\n");
		assertThat(result.detail()).isEmpty();
	}

	@Test
	void metaLineAndFollowingBlankLineAreNotInDeltaButBlankLineInBodyIs() {
		Result result = parse(SourceType.IMAGE, List.of(IMAGE_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.BAR, "지역별 매출"));
		assertThat(result.summary()).isEqualTo(IMAGE_BODY);
		assertThat(result.detail()).isEmpty();
	}

	@Test
	void blankLineAfterMetaInSeparateChunkIsNotInDelta() {
		// 문장 단위로 내보내는 이미지 요약 제한을 피하려고 PDF 로 본다
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF, SUMMARY_MAX_LENGTH);

		assertThat(parser.feed("{\"title\":\"t\"}\n")).containsExactly(new LlmOutputEvent.Meta(null, "t"));
		assertThat(parser.feed("\n")).isEmpty();
		// 줄 끝 줄바꿈은 다음 줄이 구분선이 아님을 알 때까지 보류한다
		assertThat(parser.feed("첫 문단\n")).containsExactly(summary("첫 문단"));
		assertThat(parser.feed("\n")).isEmpty();
		assertThat(parser.feed("둘째 문단")).containsExactly(summary("\n\n둘째 문단"));
		assertThat(parser.finish()).isEmpty();
	}

	@Test
	void separatorSplitsSummaryAndDetailAndDropsSurroundingBlankLines() {
		Result result = parse(SourceType.IMAGE, List.of(SEPARATOR_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.BAR, "t"));
		// 구분선이 아닌 줄(@@DETAIL@@x, @@DETAIL)은 요약, 두 번째 구분선은 상세의 일반 텍스트다
		assertThat(result.summary()).isEqualTo(SEPARATOR_SUMMARY);
		assertThat(result.detail()).isEqualTo(SEPARATOR_DETAIL);
	}

	@Test
	void separatorWithCrlf() {
		Result result = parse(SourceType.IMAGE, List.of(SEPARATOR_CRLF_OUTPUT));

		assertThat(result.summary()).isEqualTo("요약입니다.");
		assertThat(result.detail()).isEqualTo("상세입니다.\r\n");
	}

	@Test
	void separatorSplitAcrossChunksIsHeldOnlyWhileItMatches() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF, SUMMARY_MAX_LENGTH);
		parser.feed("{\"title\":\"t\"}\n");

		assertThat(parser.feed("요약\n@@DE")).containsExactly(summary("요약"));
		// 구분선과 어긋나는 순간 보류분을 바로 내보낸다
		assertThat(parser.feed("X 계속\n@@")).containsExactly(summary("\n@@DEX 계속"));
		assertThat(parser.feed("DETAIL")).isEmpty();
		assertThat(parser.feed("@@\n상세")).containsExactly(detail("상세"));
		assertThat(parser.feed(" 계속")).containsExactly(detail(" 계속"));
		assertThat(parser.finish()).isEmpty();
	}

	@Test
	void separatorAtEndWithoutNewlineGivesNoDetail() {
		Result result = parse(SourceType.PDF, List.of("{\"title\":\"t\"}\n요약\n\n@@DETAIL@@"));

		assertThat(result.summary()).isEqualTo("요약");
		assertThat(result.detail()).isEmpty();
	}

	@Test
	void separatorOnFirstBodyLineGivesEmptySummary() {
		Result result = parse(SourceType.IMAGE, List.of("{\"visualType\":\"BAR\",\"title\":\"t\"}\n@@DETAIL@@\n상세"));

		assertThat(result.summary()).isEmpty();
		assertThat(result.detail()).isEqualTo("상세");
	}

	@Test
	void incompleteSeparatorAtEndIsSummary() {
		Result result = parse(SourceType.PDF, List.of("{\"title\":\"t\"}\n요약\n@@DETA"));

		assertThat(result.summary()).isEqualTo("요약\n@@DETA");
		assertThat(result.detail()).isEmpty();
	}

	@Test
	void imageSummaryIsCutAtSentenceBoundaryButDetailIsNot() {
		Result result = parse(SourceType.IMAGE, List.of(LONG_SUMMARY_OUTPUT));

		assertThat(result.summary()).isEqualTo(LONG_SUMMARY_FIRST);
		assertThat(result.detail()).isEqualTo("상세는 자르지 않습니다." + "가".repeat(400));
	}

	@Test
	void pdfSummaryIsNotLimited() {
		String body = "가".repeat(500) + "입니다.";
		Result result = parse(SourceType.PDF, List.of("{\"title\":\"t\"}\n" + body));

		assertThat(result.summary()).isEqualTo(body);
	}

	@Test
	void summaryMaxLengthComesFromConstructor() {
		String output = String.join("", FakeLlmClient.CHUNKS);
		String firstSentence = "이 자료는 1월부터 6월까지의 월별 방문자 수를 나타낸 꺾은선 그래프입니다.";

		Result image = parse(SourceType.IMAGE, 50, List.of(output));
		// 첫 문장 뒤 줄바꿈(한 글자 문장)까지는 50자 안이고, 다음 문장을 붙이면 넘친다
		assertThat(image.summary()).isEqualTo(firstSentence + "\n");
		assertThat(image.detail()).isEqualTo(FAKE_DETAIL);

		String pdfBody = "가".repeat(100) + "입니다.";
		assertThat(parse(SourceType.PDF, 50, List.of("{\"title\":\"t\"}\n" + pdfBody)).summary()).isEqualTo(pdfBody);
	}

	@Test
	void nonPositiveSummaryMaxLengthIsRejectedForImage() {
		assertThatThrownBy(() -> new LlmOutputParser(SourceType.IMAGE, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void skipsBlankLinesBeforeMetaLine() {
		Result result = parse(SourceType.IMAGE, List.of(LEADING_BLANK_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.OTHER, "기타 자료"));
		assertThat(result.summary()).isEqualTo("본문입니다.");
	}

	@Test
	void emitsMetaAsSoonAsFirstLineEndsAndDeltaImmediately() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF, SUMMARY_MAX_LENGTH);

		assertThat(parser.feed("{\"title\":")).isEmpty();
		assertThat(parser.feed("\"제목\"}\n본문 ")).containsExactly(
				new LlmOutputEvent.Meta(null, "제목"),
				summary("본문 "));
		assertThat(parser.feed("계속")).containsExactly(summary("계속"));
		assertThat(parser.finish()).isEmpty();
	}

	@Test
	void imageSummaryIsEmittedSentenceBySentence() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE, SUMMARY_MAX_LENGTH);
		parser.feed("{\"visualType\":\"LINE\",\"title\":\"제목\"}\n");

		assertThat(parser.feed("첫 문장")).isEmpty();
		assertThat(parser.feed("입니다. 둘째")).containsExactly(summary("첫 문장입니다."));
		assertThat(parser.finish()).containsExactly(summary(" 둘째"));
	}

	@Test
	void handlesCrlf() {
		Result result = parse(SourceType.IMAGE, List.of(IMAGE_CRLF_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.PIE, "점유율"));
		assertThat(result.summary()).isEqualTo(IMAGE_CRLF_BODY);
	}

	@Test
	void unreadableIgnoresFollowingText() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE, SUMMARY_MAX_LENGTH);

		assertThat(parser.feed("{\"unreadable\":true}\n무시")).containsExactly(new LlmOutputEvent.Unreadable());
		assertThat(parser.feed("이것도 무시")).isEmpty();
		assertThat(parser.finish()).isEmpty();
	}

	@Test
	void unreadableWithoutNewlineIsHandledOnFinish() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE, SUMMARY_MAX_LENGTH);

		assertThat(parser.feed("{\"unreadable\":")).isEmpty();
		assertThat(parser.feed("true}")).isEmpty();
		assertThat(parser.finish()).containsExactly(new LlmOutputEvent.Unreadable());
	}

	@Test
	void metaOnlyWithoutNewlineIsHandledOnFinish() {
		Result result = parse(SourceType.IMAGE, List.of("{\"visualType\":\"TABLE\",", "\"title\":\"표\"}"));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.TABLE, "표"));
		assertThat(result.summary()).isEmpty();
		assertThat(result.detail()).isEmpty();
	}

	@Test
	void pdfOutputHasTitleOnly() {
		Result result = parse(SourceType.PDF, List.of(PDF_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(null, "회의록 요약"));
		assertThat(result.summary()).isEqualTo("회의에서는 예산을 논의했습니다.");
	}

	@Test
	void imageWithoutVisualTypeThrows() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE, SUMMARY_MAX_LENGTH);

		assertThatThrownBy(() -> parser.feed(PDF_OUTPUT)).isInstanceOf(LlmOutputFormatException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"제목: 월별 방문자\n본문",
			"```json\n{\"visualType\":\"LINE\",\"title\":\"t\"}\n본문",
			"[\"LINE\",\"t\"]\n본문",
			"{\"visualType\":\"LINE\",\"title\":\"t\"} 덧붙임\n본문",
			"{\"visualType\":\"SCATTER\",\"title\":\"t\"}\n본문",
			"{\"visualType\":3,\"title\":\"t\"}\n본문",
			"{\"visualType\":\"LINE\"}\n본문",
			"{\"visualType\":\"LINE\",\"title\":\"  \"}\n본문",
			"{\"unreadable\":\"yes\"}\n",
			"\n",
			"\n \r\n\t\n",
			"  \r\n  "
	})
	void malformedImageOutputThrows(String output) {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE, SUMMARY_MAX_LENGTH);

		assertThatThrownBy(() -> {
			parser.feed(output);
			parser.finish();
		}).isInstanceOf(LlmOutputFormatException.class);
	}

	@Test
	void unreadableIsNotAllowedForPdf() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF, SUMMARY_MAX_LENGTH);

		assertThatThrownBy(() -> parser.feed(UNREADABLE_OUTPUT)).isInstanceOf(LlmOutputFormatException.class);
	}

	@Test
	void emptyOutputThrowsOnFinish() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE, SUMMARY_MAX_LENGTH);

		assertThatThrownBy(parser::finish).isInstanceOf(LlmOutputFormatException.class);
	}

	@Test
	void feedAfterFinishIsRejected() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF, SUMMARY_MAX_LENGTH);
		parser.feed(PDF_OUTPUT);
		parser.finish();

		assertThatThrownBy(() -> parser.feed("더")).isInstanceOf(IllegalStateException.class);
	}
}
