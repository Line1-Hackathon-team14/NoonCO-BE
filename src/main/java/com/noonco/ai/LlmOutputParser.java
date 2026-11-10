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
 * 그 뒤 텍스트는 받는 즉시 {@link LlmOutputEvent.Delta} 로 내보낸다.
 * 스레드 안전하지 않다.
 */
public class LlmOutputParser {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private enum State {
		HEADER, BODY_START, BODY, UNREADABLE, FINISHED
	}

	private final SourceType sourceType;
	private final StringBuilder header = new StringBuilder();
	private State state = State.HEADER;

	public LlmOutputParser(SourceType sourceType) {
		if (sourceType == null) {
			throw new IllegalArgumentException("sourceType 은 필수입니다.");
		}
		this.sourceType = sourceType;
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
			int start = 0;
			while (start < chunk.length() && (chunk.charAt(start) == '\n' || chunk.charAt(start) == '\r')) {
				start++;
			}
			chunk = chunk.substring(start);
			if (chunk.isEmpty()) {
				return events;
			}
			state = State.BODY;
		}
		events.add(new LlmOutputEvent.Delta(chunk));
		return events;
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
		state = State.FINISHED;
		return events;
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
