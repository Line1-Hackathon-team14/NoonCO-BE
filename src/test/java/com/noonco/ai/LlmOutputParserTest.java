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

	private static final String FAKE_OUTPUT = String.join("", FakeLlmClient.CHUNKS);
	private static final String FAKE_BODY = FAKE_OUTPUT.substring(FAKE_OUTPUT.indexOf('\n') + 1);
	private static final LlmOutputEvent.Meta FAKE_META = new LlmOutputEvent.Meta(VisualType.LINE, "월별 방문자 수 추이");

	// 메타 뒤 빈 줄(제외 대상)과 본문 중간 빈 줄(그대로 전달)을 함께 담는다.
	private static final String IMAGE_OUTPUT = "{\"visualType\":\"bar\",\"title\":\"지역별 매출\"}\n\n서울이 가장 높습니다.\n\n부산이 뒤를 잇습니다.";
	private static final String IMAGE_BODY = "서울이 가장 높습니다.\n\n부산이 뒤를 잇습니다.";
	private static final String IMAGE_CRLF_OUTPUT = "{\"visualType\":\"PIE\",\"title\":\"점유율\"}\r\n\r\nA사가 절반입니다.\r\n\r\nB사가 나머지입니다.";
	private static final String IMAGE_CRLF_BODY = "A사가 절반입니다.\r\n\r\nB사가 나머지입니다.";
	private static final String LEADING_BLANK_OUTPUT = "\n \r\n\t\n{\"visualType\":\"OTHER\",\"title\":\"기타 자료\"}\n본문입니다.";
	private static final String PDF_OUTPUT = "{\"title\":\"회의록 요약\"}\n회의에서는 예산을 논의했습니다.";
	private static final String UNREADABLE_OUTPUT = "{\"unreadable\":true}\n무시되어야 하는 텍스트";

	/**
	 * 파싱 결과를 비교하기 쉬운 형태로 정리한 것. Delta 는 이어 붙인다.
	 */
	private record Result(List<LlmOutputEvent> heads, String body) {
	}

	private static Result parse(SourceType sourceType, List<String> chunks) {
		LlmOutputParser parser = new LlmOutputParser(sourceType);
		List<LlmOutputEvent> events = new ArrayList<>();
		chunks.forEach(chunk -> events.addAll(parser.feed(chunk)));
		events.addAll(parser.finish());

		List<LlmOutputEvent> heads = new ArrayList<>();
		StringBuilder body = new StringBuilder();
		for (LlmOutputEvent event : events) {
			if (event instanceof LlmOutputEvent.Delta delta) {
				assertThat(delta.text()).isNotEmpty();
				body.append(delta.text());
			} else {
				assertThat(body).as("Meta/Unreadable 는 Delta 보다 먼저 나와야 한다").isEmpty();
				heads.add(event);
			}
		}
		return new Result(heads, body.toString());
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
				Arguments.of(SourceType.PDF, PDF_OUTPUT)
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

	@Test
	void fakeClientOutputGivesLineMetaAndBody() {
		Result result = parse(SourceType.IMAGE, FakeLlmClient.CHUNKS);

		assertThat(result.heads()).containsExactly(FAKE_META);
		assertThat(result.body()).isEqualTo(FAKE_BODY).startsWith("이 자료는");
	}

	@Test
	void metaLineAndFollowingBlankLineAreNotInDeltaButBlankLineInBodyIs() {
		Result result = parse(SourceType.IMAGE, List.of(IMAGE_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.BAR, "지역별 매출"));
		assertThat(result.body()).isEqualTo(IMAGE_BODY);
	}

	@Test
	void blankLineAfterMetaInSeparateChunkIsNotInDelta() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

		assertThat(parser.feed("{\"visualType\":\"BAR\",\"title\":\"t\"}\n")).containsExactly(
				new LlmOutputEvent.Meta(VisualType.BAR, "t"));
		assertThat(parser.feed("\n")).isEmpty();
		assertThat(parser.feed("첫 문단\n")).containsExactly(new LlmOutputEvent.Delta("첫 문단\n"));
		assertThat(parser.feed("\n")).containsExactly(new LlmOutputEvent.Delta("\n"));
		assertThat(parser.feed("둘째 문단")).containsExactly(new LlmOutputEvent.Delta("둘째 문단"));
	}

	@Test
	void skipsBlankLinesBeforeMetaLine() {
		Result result = parse(SourceType.IMAGE, List.of(LEADING_BLANK_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.OTHER, "기타 자료"));
		assertThat(result.body()).isEqualTo("본문입니다.");
	}

	@Test
	void emitsMetaAsSoonAsFirstLineEndsAndDeltaImmediately() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

		assertThat(parser.feed("{\"visualType\":\"LINE\",")).isEmpty();
		assertThat(parser.feed("\"title\":\"제목\"}\n본문 ")).containsExactly(
				new LlmOutputEvent.Meta(VisualType.LINE, "제목"),
				new LlmOutputEvent.Delta("본문 "));
		assertThat(parser.feed("계속")).containsExactly(new LlmOutputEvent.Delta("계속"));
		assertThat(parser.finish()).isEmpty();
	}

	@Test
	void handlesCrlf() {
		Result result = parse(SourceType.IMAGE, List.of(IMAGE_CRLF_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.PIE, "점유율"));
		assertThat(result.body()).isEqualTo(IMAGE_CRLF_BODY);
	}

	@Test
	void unreadableIgnoresFollowingText() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

		assertThat(parser.feed("{\"unreadable\":true}\n무시")).containsExactly(new LlmOutputEvent.Unreadable());
		assertThat(parser.feed("이것도 무시")).isEmpty();
		assertThat(parser.finish()).isEmpty();
	}

	@Test
	void unreadableWithoutNewlineIsHandledOnFinish() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

		assertThat(parser.feed("{\"unreadable\":")).isEmpty();
		assertThat(parser.feed("true}")).isEmpty();
		assertThat(parser.finish()).containsExactly(new LlmOutputEvent.Unreadable());
	}

	@Test
	void metaOnlyWithoutNewlineIsHandledOnFinish() {
		Result result = parse(SourceType.IMAGE, List.of("{\"visualType\":\"TABLE\",", "\"title\":\"표\"}"));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(VisualType.TABLE, "표"));
		assertThat(result.body()).isEmpty();
	}

	@Test
	void pdfOutputHasTitleOnly() {
		Result result = parse(SourceType.PDF, List.of(PDF_OUTPUT));

		assertThat(result.heads()).containsExactly(new LlmOutputEvent.Meta(null, "회의록 요약"));
		assertThat(result.body()).isEqualTo("회의에서는 예산을 논의했습니다.");
	}

	@Test
	void imageWithoutVisualTypeThrows() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

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
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

		assertThatThrownBy(() -> {
			parser.feed(output);
			parser.finish();
		}).isInstanceOf(LlmOutputFormatException.class);
	}

	@Test
	void unreadableIsNotAllowedForPdf() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF);

		assertThatThrownBy(() -> parser.feed(UNREADABLE_OUTPUT)).isInstanceOf(LlmOutputFormatException.class);
	}

	@Test
	void emptyOutputThrowsOnFinish() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.IMAGE);

		assertThatThrownBy(parser::finish).isInstanceOf(LlmOutputFormatException.class);
	}

	@Test
	void feedAfterFinishIsRejected() {
		LlmOutputParser parser = new LlmOutputParser(SourceType.PDF);
		parser.feed(PDF_OUTPUT);
		parser.finish();

		assertThatThrownBy(() -> parser.feed("더")).isInstanceOf(IllegalStateException.class);
	}
}
