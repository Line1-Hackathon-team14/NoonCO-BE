package com.noonco.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.noonco.analysis.entity.VisualType;
import com.noonco.material.entity.SourceType;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM 스트림 텍스트 조각을 이벤트로 바꾸는 상태 있는 파서. 스트림 1건당 1개씩 만들어 쓴다.
 * 앞쪽 빈 줄을 건너뛴 첫 줄은 메타 JSON 으로 해석해 {@link LlmOutputEvent.Meta} 또는 {@link LlmOutputEvent.Unreadable} 을 만들고,
 * 그 뒤 텍스트는 {@link LlmOutputEvent.Delta} 로 내보낸다.
 * 본문은 {@value #DETAIL_SEPARATOR} 만 있는 줄(앞뒤 공백 허용)을 경계로 요약과 상세로 나뉜다. 구분선이 없으면 전부 요약이고,
 * 두 번째 구분선부터는 상세 본문의 일반 텍스트다.
 * 요약 구간에서는 줄 시작이 구분선 접두어와 일치하는 동안만 그 줄과 앞 줄바꿈을 보류하고, 나머지는 받는 즉시 내보낸다.
 * 상세 구간은 받는 즉시 내보낸다.
 * 이미지 요약은 {@link SummaryLimiter} 로 길이를 제한하므로 문장 단위로 나간다.
 * 스레드 안전하지 않다.
 */
public class LlmOutputParser {

	static final String DETAIL_SEPARATOR = "@@DETAIL@@";

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private enum State {
		HEADER, BODY_START, SUMMARY, DETAIL_START, DETAIL, UNREADABLE, FINISHED
	}

	private final SourceType sourceType;
	// PDF 요약은 길이를 제한하지 않는다
	private final SummaryLimiter summaryLimiter;
	private final StringBuilder header = new StringBuilder();
	// 요약 구간에서 구분선 줄일 수 있어 보류 중인 줄바꿈들과 그 뒤 줄의 앞부분
	private final StringBuilder heldNewlines = new StringBuilder();
	private final StringBuilder heldLine = new StringBuilder();
	private boolean holding;
	private State state = State.HEADER;

	/**
	 * @param summaryMaxLength 이미지 요약 최대 길이(공백 포함 Unicode code point 수). PDF 에는 쓰지 않는다
	 */
	public LlmOutputParser(SourceType sourceType, int summaryMaxLength) {
		if (sourceType == null) {
			throw new IllegalArgumentException("sourceType 은 필수입니다.");
		}
		this.sourceType = sourceType;
		this.summaryLimiter = sourceType == SourceType.IMAGE ? new SummaryLimiter(summaryMaxLength) : null;
	}

	/**
	 * 텍스트 조각 하나를 넣고, 이 시점에 확정된 이벤트를 반환한다.
	 *
	 * @throws LlmOutputFormatException 첫 줄 메타가 형식에 맞지 않을 때
	 */
	public List<LlmOutputEvent> feed(String chunk) {
		if (state == State.FINISHED) {
			throw new IllegalStateException("finish 이후에는 조각을 넣을 수 없습니다.");
		}
		List<LlmOutputEvent> events = new ArrayList<>();
		if (chunk == null || chunk.isEmpty() || state == State.UNREADABLE) {
			return events;
		}
		if (state == State.HEADER) {
			int newline;
			while ((newline = chunk.indexOf('\n')) >= 0) {
				header.append(chunk, 0, newline);
				chunk = chunk.substring(newline + 1);
				String line = header.toString();
				header.setLength(0);
				// 메타 줄 앞의 빈 줄(공백·\r 만 있는 줄)은 건너뛴다.
				if (!line.isBlank()) {
					events.add(parseHeader(line));
					break;
				}
			}
			if (state == State.HEADER) {
				header.append(chunk);
				return events;
			}
			if (state == State.UNREADABLE) {
				return events;
			}
		}
		if (state == State.BODY_START) {
			// 메타 줄 바로 뒤의 빈 줄은 본문에 넣지 않는다.
			chunk = skipNewlines(chunk);
			if (chunk.isEmpty()) {
				return events;
			}
			state = State.SUMMARY;
			holding = true;
		}
		if (state == State.SUMMARY) {
			chunk = feedSummary(chunk, events);
		}
		if (state == State.DETAIL_START) {
			// 구분선 바로 뒤의 빈 줄은 상세에 넣지 않는다.
			chunk = skipNewlines(chunk);
			if (chunk.isEmpty()) {
				return events;
			}
			state = State.DETAIL;
		}
		if (state == State.DETAIL && !chunk.isEmpty()) {
			events.add(new LlmOutputEvent.Delta(LlmOutputEvent.Section.DETAIL, chunk));
		}
		return events;
	}

	/**
	 * 요약 텍스트를 내보내고, 구분선을 만나면 그 뒤에 남은 텍스트를 반환한다(없으면 빈 문자열).
	 */
	private String feedSummary(String chunk, List<LlmOutputEvent> events) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < chunk.length(); i++) {
			char c = chunk.charAt(i);
			if (!holding) {
				if (isNewline(c)) {
					holding = true;
					heldNewlines.append(c);
				} else {
					out.append(c);
				}
				continue;
			}
			if (isNewline(c) && heldLine.isEmpty()) {
				heldNewlines.append(c);
				continue;
			}
			if (c == '\n') {
				if (isSeparator(heldLine)) {
					// 구분선 줄과 그 앞 줄바꿈은 버린다
					clearHeld();
					emitSummary(out, events);
					finishSummary(events);
					state = State.DETAIL_START;
					return chunk.substring(i + 1);
				}
				releaseHeld(out);
				heldNewlines.append(c);
				continue;
			}
			heldLine.append(c);
			if (!couldBeSeparator(heldLine)) {
				releaseHeld(out);
				holding = false;
			}
		}
		emitSummary(out, events);
		return "";
	}

	/**
	 * 스트림이 끝났을 때 호출한다. 메타 줄 끝의 줄바꿈이 끝까지 나오지 않았으면 모은 텍스트 전체를 메타 줄로 보고 처리한다.
	 *
	 * @throws LlmOutputFormatException 첫 줄 메타가 형식에 맞지 않을 때
	 */
	public List<LlmOutputEvent> finish() {
		if (state == State.FINISHED) {
			throw new IllegalStateException("finish 는 한 번만 호출할 수 있습니다.");
		}
		List<LlmOutputEvent> events = new ArrayList<>();
		if (state == State.HEADER) {
			events.add(parseHeader(header.toString()));
			header.setLength(0);
		}
		if (state == State.SUMMARY) {
			// 줄바꿈 없이 끝난 구분선 줄은 버리고, 그 밖의 보류분은 요약이다
			if (isSeparator(heldLine)) {
				clearHeld();
			} else {
				StringBuilder out = new StringBuilder();
				releaseHeld(out);
				emitSummary(out, events);
			}
			finishSummary(events);
		}
		state = State.FINISHED;
		return events;
	}

	private void emitSummary(CharSequence text, List<LlmOutputEvent> events) {
		if (text.isEmpty()) {
			return;
		}
		addSummary(summaryLimiter == null ? text.toString() : summaryLimiter.feed(text), events);
	}

	private void finishSummary(List<LlmOutputEvent> events) {
		if (summaryLimiter != null) {
			addSummary(summaryLimiter.finish(), events);
		}
	}

	private static void addSummary(String text, List<LlmOutputEvent> events) {
		if (!text.isEmpty()) {
			events.add(new LlmOutputEvent.Delta(LlmOutputEvent.Section.SUMMARY, text));
		}
	}

	private void releaseHeld(StringBuilder out) {
		out.append(heldNewlines).append(heldLine);
		clearHeld();
	}

	private void clearHeld() {
		heldNewlines.setLength(0);
		heldLine.setLength(0);
	}

	private static boolean isSeparator(CharSequence line) {
		return line.toString().strip().equals(DETAIL_SEPARATOR);
	}

	/**
	 * 앞 공백을 뺀 줄이 구분선 접두어이거나, 구분선 뒤에 공백(\r 포함)만 있으면 아직 구분선일 수 있다.
	 */
	private static boolean couldBeSeparator(CharSequence line) {
		String text = line.toString().stripLeading();
		if (DETAIL_SEPARATOR.startsWith(text)) {
			return true;
		}
		return text.startsWith(DETAIL_SEPARATOR) && text.substring(DETAIL_SEPARATOR.length()).isBlank();
	}

	private static boolean isNewline(char c) {
		return c == '\n' || c == '\r';
	}

	private static String skipNewlines(String chunk) {
		int start = 0;
		while (start < chunk.length() && isNewline(chunk.charAt(start))) {
			start++;
		}
		return chunk.substring(start);
	}

	private LlmOutputEvent parseHeader(String line) {
		String json = line.strip();
		if (json.isEmpty()) {
			throw new LlmOutputFormatException("메타 줄이 없습니다(출력이 비어 있거나 빈 줄뿐입니다).");
		}
		JsonNode node;
		try {
			node = MAPPER.readTree(json);
		} catch (JacksonException e) {
			throw new LlmOutputFormatException("메타 줄이 JSON 이 아닙니다: " + json, e);
		}
		if (node == null || !node.isObject()) {
			throw new LlmOutputFormatException("메타 줄이 JSON 객체가 아닙니다: " + json);
		}

		JsonNode unreadable = node.get("unreadable");
		if (unreadable != null && !unreadable.isNull()) {
			if (!unreadable.isBoolean()) {
				throw new LlmOutputFormatException("unreadable 값이 boolean 이 아닙니다: " + json);
			}
			if (unreadable.booleanValue()) {
				if (sourceType != SourceType.IMAGE) {
					throw new LlmOutputFormatException("unreadable 은 이미지 출력에서만 허용됩니다: " + json);
				}
				state = State.UNREADABLE;
				return new LlmOutputEvent.Unreadable();
			}
		}

		String title = requireText(node, "title", json);
		VisualType visualType = sourceType == SourceType.IMAGE ? parseVisualType(node, json) : null;
		state = State.BODY_START;
		return new LlmOutputEvent.Meta(visualType, title);
	}

	private static VisualType parseVisualType(JsonNode node, String json) {
		String value = requireText(node, "visualType", json);
		try {
			return VisualType.valueOf(value.strip().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new LlmOutputFormatException("알 수 없는 visualType 입니다: " + value, e);
		}
	}

	private static String requireText(JsonNode node, String field, String json) {
		JsonNode value = node.get(field);
		if (value == null || !value.isString() || value.stringValue().isBlank()) {
			throw new LlmOutputFormatException(field + " 가 없거나 비어 있습니다: " + json);
		}
		return value.stringValue();
	}
}
