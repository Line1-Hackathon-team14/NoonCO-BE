package com.noonco.ai;

/**
 * 요약 해설을 최대 길이(공백 포함 Unicode code point 수) 안에서 문장 단위로 내보낸다.
 * 이미 보낸 텍스트는 되돌릴 수 없으므로 문장이 끝날 때까지 모았다가 보내고,
 * 다음 문장을 붙이면 최대 길이를 넘는 시점에 멈춰 나머지를 버린다.
 * 보낸 문장이 하나도 없는데 최대 길이를 넘으면 최대 길이에서 자른다.
 * 문장 끝은 줄바꿈, '!', '?', '.' 이고, 숫자 바로 뒤의 '.' 은 공백·줄바꿈·끝이 뒤따를 때만 문장 끝으로 본다(소수점 구분).
 * 스레드 안전하지 않다.
 */
class SummaryLimiter {

	private static final int UNDECIDED = -1;

	private final int maxCodePoints;
	private final StringBuilder pending = new StringBuilder();
	private int sentCodePoints;
	private boolean stopped;

	SummaryLimiter(int maxCodePoints) {
		if (maxCodePoints < 1) {
			throw new IllegalArgumentException("요약 최대 길이는 1 이상이어야 합니다: " + maxCodePoints);
		}
		this.maxCodePoints = maxCodePoints;
	}

	/**
	 * 텍스트를 넣고, 지금 보내도 되는 텍스트를 반환한다(없으면 빈 문자열).
	 */
	String feed(CharSequence text) {
		if (stopped) {
			return "";
		}
		pending.append(text);
		return drain(false);
	}

	/**
	 * 요약 구간이 끝났을 때 호출한다. 끝은 문장 끝으로 보므로 남은 텍스트도 길이 안이면 보낸다.
	 */
	String finish() {
		if (stopped) {
			return "";
		}
		String out = drain(true);
		stopped = true;
		return out;
	}

	private String drain(boolean atEnd) {
		StringBuilder out = new StringBuilder();
		int end;
		while (!stopped && (end = sentenceEnd(atEnd)) > 0) {
			take(end, out);
		}
		// 끝나지 않은 문장만으로도 최대 길이를 넘으면 더 기다릴 필요가 없다
		if (!stopped && sentCodePoints + pending.codePointCount(0, pending.length()) > maxCodePoints) {
			cut(out);
		}
		return out.toString();
	}

	private void take(int end, StringBuilder out) {
		int length = pending.codePointCount(0, end);
		if (sentCodePoints + length > maxCodePoints) {
			cut(out);
			return;
		}
		out.append(pending, 0, end);
		pending.delete(0, end);
		sentCodePoints += length;
	}

	/**
	 * 넘치는 문장은 보내지 않고 멈춘다. 보낸 문장이 없으면 최대 길이에서 잘라 보낸다.
	 */
	private void cut(StringBuilder out) {
		if (sentCodePoints == 0) {
			out.append(pending, 0, pending.offsetByCodePoints(0, maxCodePoints));
			sentCodePoints = maxCodePoints;
		}
		pending.setLength(0);
		stopped = true;
	}

	/**
	 * pending 의 첫 문장 끝(제외 위치)을 반환한다. 없거나 아직 판단할 수 없으면 0 이하.
	 */
	private int sentenceEnd(boolean atEnd) {
		for (int i = 0; i < pending.length(); i++) {
			char c = pending.charAt(i);
			if (c == '\n' || c == '!' || c == '?') {
				return i + 1;
			}
			if (c != '.') {
				continue;
			}
			if (i == 0 || !Character.isDigit(pending.charAt(i - 1))) {
				return i + 1;
			}
			if (i + 1 == pending.length()) {
				return atEnd ? i + 1 : UNDECIDED;
			}
			if (Character.isWhitespace(pending.charAt(i + 1))) {
				return i + 1;
			}
		}
		return atEnd ? pending.length() : 0;
	}
}
