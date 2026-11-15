package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SummaryLimiterTest {

	// 기본 설정값(analysis.stream.summary-max-length)
	private static final int MAX = 350;

	@Test
	void lowerMaxCutsAtSentenceBoundary() {
		SummaryLimiter limiter = new SummaryLimiter(50);
		String first = "가".repeat(30) + "입니다.";
		String second = " " + "나".repeat(20) + "입니다.";

		assertThat(limiter.feed(first + second) + limiter.finish()).isEqualTo(first);
	}

	@Test
	void lowerMaxWithoutSentenceEndCutsAtMax() {
		SummaryLimiter limiter = new SummaryLimiter(50);

		assertThat(limiter.feed("😀".repeat(80)) + limiter.finish()).isEqualTo("😀".repeat(50));
	}

	@Test
	void rejectsNonPositiveMax() {
		assertThatThrownBy(() -> new SummaryLimiter(0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static String limitWhole(String text) {
		SummaryLimiter limiter = new SummaryLimiter(MAX);
		return limiter.feed(text) + limiter.finish();
	}

	/**
	 * 한 글자(char)씩 넣는다. 서로게이트 쌍도 갈라서 넣는다.
	 */
	private static String limitByChar(String text) {
		SummaryLimiter limiter = new SummaryLimiter(MAX);
		StringBuilder out = new StringBuilder();
		text.chars().forEach(c -> out.append(limiter.feed(String.valueOf((char)c))));
		return out.append(limiter.finish()).toString();
	}

	private static int codePoints(String text) {
		return text.codePointCount(0, text.length());
	}

	static Stream<String> samples() {
		return Stream.of(
				"가".repeat(299) + ".",
				"가".repeat(200) + ". " + "나".repeat(100) + "입니다.\n" + "다".repeat(40) + "!",
				"가".repeat(300) + "입니다. " + "나".repeat(100) + "입니다.",
				"가".repeat(400),
				"😀".repeat(200) + ". " + "😀".repeat(200) + ".",
				"값은 1.5배입니다. " + "가".repeat(338) + " 3.\n다음",
				"가".repeat(340) + " 약 1.5배입니다.");
	}

	@ParameterizedTest
	@MethodSource("samples")
	void sameResultRegardlessOfChunking(String text) {
		String whole = limitWhole(text);

		assertThat(limitByChar(text)).isEqualTo(whole);
		assertThat(codePoints(whole)).isLessThanOrEqualTo(MAX);
		assertThat(text).startsWith(whole);
	}

	@Test
	void textUpTo300IsUnchanged() {
		String text = "가".repeat(150) + "입니다.\n" + "나".repeat(140) + "이에요!";

		assertThat(codePoints(text)).isLessThanOrEqualTo(300);
		assertThat(limitWhole(text)).isEqualTo(text);
	}

	@Test
	void textBetween300And350IsUnchanged() {
		String text = "가".repeat(200) + "입니다. " + "나".repeat(120) + "까요?";

		assertThat(codePoints(text)).isBetween(301, MAX);
		assertThat(limitWhole(text)).isEqualTo(text);
	}

	@Test
	void textOfExactly350WithoutSentenceEndIsUnchanged() {
		String text = "가".repeat(MAX);

		assertThat(limitWhole(text)).isEqualTo(text);
	}

	@Test
	void over350IsCutAtLastSentenceThatFits() {
		String first = "가".repeat(200) + "입니다.";
		String second = " " + "나".repeat(100) + "입니다.";
		String third = " " + "다".repeat(100) + "입니다.";

		assertThat(codePoints(first + second)).isLessThanOrEqualTo(MAX);
		assertThat(codePoints(first + second + third)).isGreaterThan(MAX);
		assertThat(limitWhole(first + second + third + " 끝.")).isEqualTo(first + second);
	}

	@Test
	void nothingAfterTheCutIsSentEvenIfItWouldFit() {
		String first = "가".repeat(300) + ".";
		String text = first + " " + "나".repeat(100) + ". 짧다.";

		assertThat(limitWhole(text)).isEqualTo(first);
	}

	@Test
	void overflowingSentenceIsNotSentBeforeItEnds() {
		SummaryLimiter limiter = new SummaryLimiter(MAX);

		assertThat(limiter.feed("가".repeat(300) + ". ")).isEqualTo("가".repeat(300) + ".");
		// 이 문장이 넘칠지는 끝까지 알 수 없지만, 넘치는 순간 버리고 그 앞부분도 보내지 않는다
		assertThat(limiter.feed("나".repeat(40))).isEmpty();
		assertThat(limiter.feed("나".repeat(20))).isEmpty();
		assertThat(limiter.feed("입니다.")).isEmpty();
		assertThat(limiter.finish()).isEmpty();
	}

	@Test
	void withoutSentenceEndCutsAt350() {
		String text = "가".repeat(200) + "," + "나".repeat(200) + ". 다음 문장.";

		assertThat(limitWhole(text)).isEqualTo(text.substring(0, MAX));
	}

	@Test
	void decimalPointIsNotSentenceEnd() {
		String first = "가".repeat(300) + "입니다.";
		// "1." 에서 문장이 끝난다고 보면 " 값은 1." 까지 보내게 된다
		String text = first + " 값은 1.5배이며 " + "나".repeat(60) + "입니다.";

		assertThat(limitWhole(text)).isEqualTo(first);
	}

	@Test
	void digitFollowedByPeriodAndSpaceOrNewlineOrEndIsSentenceEnd() {
		String first = "가".repeat(340) + " 약 3.";

		assertThat(limitWhole(first + " 다음 문장입니다.")).isEqualTo(first);
		// 줄바꿈은 그 자체로 한 글자짜리 문장이라 길이 안이면 함께 나간다
		assertThat(limitWhole(first + "\n다음 문장입니다.")).isEqualTo(first + "\n");

		SummaryLimiter limiter = new SummaryLimiter(MAX);
		assertThat(limiter.feed("버전 2.")).as("뒤 글자를 봐야 판단할 수 있다").isEmpty();
		assertThat(limiter.finish()).isEqualTo("버전 2.");
	}

	@Test
	void sentenceIsSentAsSoonAsItEnds() {
		SummaryLimiter limiter = new SummaryLimiter(MAX);

		assertThat(limiter.feed("첫 문장")).isEmpty();
		assertThat(limiter.feed("입니다. 둘째")).isEqualTo("첫 문장입니다.");
		assertThat(limiter.feed(" 줄\n셋")).isEqualTo(" 둘째 줄\n");
		assertThat(limiter.feed("째 4.5")).isEmpty();
		assertThat(limiter.finish()).isEqualTo("셋째 4.5");
	}

	@Test
	void countsCodePointsNotChars() {
		// 이모지는 char 2개, code point 1개
		String first = "😀".repeat(300) + ".";
		String text = first + " " + "😀".repeat(40) + ".";

		assertThat(first.length()).isGreaterThan(MAX);
		assertThat(limitWhole(text)).isEqualTo(text);
		assertThat(limitWhole(text + " 넘치는 문장.")).isEqualTo(first + " " + "😀".repeat(40) + ".");
	}

	@Test
	void emojiWithoutSentenceEndIsCutAt350CodePointsWithoutSplittingSurrogates() {
		String result = limitByChar("😀".repeat(400));

		assertThat(result).isEqualTo("😀".repeat(MAX));
	}

	@Test
	void finishAfterCutReturnsNothing() {
		SummaryLimiter limiter = new SummaryLimiter(MAX);

		assertThat(limiter.feed("가".repeat(MAX + 1))).isEqualTo("가".repeat(MAX));
		assertThat(limiter.feed("더.")).isEmpty();
		assertThat(limiter.finish()).isEmpty();
	}

	@Test
	void sumOfSentencesAtExactly350IsKept() {
		String text = IntStream.range(0, 7).mapToObj(i -> "가".repeat(49) + ".").reduce("", String::concat);

		assertThat(codePoints(text)).isEqualTo(MAX);
		assertThat(limitWhole(text + "나")).isEqualTo(text);
	}
}
