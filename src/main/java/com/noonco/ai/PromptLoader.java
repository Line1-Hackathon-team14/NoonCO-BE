package com.noonco.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.noonco.material.entity.SourceType;
import com.noonco.user.entity.Difficulty;

/**
 * prompts/{image|pdf}_{easy|normal|hard}.txt 를 앱 시작 시 모두 읽어 둔다.
 * 파일 첫 줄은 "# version: v1" 형식이어야 하며, 이 줄은 본문에서 빠지고 버전 값으로 따로 보관된다.
 * 파일이 없거나 형식이 틀리면 빈 생성 단계에서 실패해 앱이 뜨지 않는다.
 */
@Component
public class PromptLoader {

	static final String DEFAULT_LOCATION = "classpath:prompts/";
	// analysis.prompt_version 컬럼 길이
	static final int MAX_VERSION_LENGTH = 20;

	private static final Pattern VERSION_LINE = Pattern.compile("^# version: (\\S+)$");

	private final Map<SourceType, Map<Difficulty, Prompt>> prompts;

	@Autowired
	public PromptLoader(ResourceLoader resourceLoader) {
		this(resourceLoader, DEFAULT_LOCATION);
	}

	PromptLoader(ResourceLoader resourceLoader, String location) {
		Map<SourceType, Map<Difficulty, Prompt>> loaded = new EnumMap<>(SourceType.class);
		for (SourceType sourceType : SourceType.values()) {
			Map<Difficulty, Prompt> byDifficulty = new EnumMap<>(Difficulty.class);
			for (Difficulty difficulty : Difficulty.values()) {
				String path = location + fileName(sourceType, difficulty);
				byDifficulty.put(difficulty, parse(path, read(resourceLoader.getResource(path), path)));
			}
			loaded.put(sourceType, Collections.unmodifiableMap(byDifficulty));
		}
		this.prompts = Collections.unmodifiableMap(loaded);
	}

	public Prompt get(SourceType sourceType, Difficulty difficulty) {
		return prompts.get(sourceType).get(difficulty);
	}

	static String fileName(SourceType sourceType, Difficulty difficulty) {
		return sourceType.name().toLowerCase(Locale.ROOT) + "_" + difficulty.name().toLowerCase(Locale.ROOT) + ".txt";
	}

	private static String read(Resource resource, String path) {
		if (!resource.exists()) {
			throw new IllegalStateException("프롬프트 파일이 없습니다: " + path);
		}
		try (InputStream in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("프롬프트 파일을 읽지 못했습니다: " + path, e);
		}
	}

	static Prompt parse(String path, String raw) {
		String text = raw.startsWith("﻿") ? raw.substring(1) : raw;
		int newline = text.indexOf('\n');
		String firstLine = (newline < 0 ? text : text.substring(0, newline)).strip();
		String body = newline < 0 ? "" : text.substring(newline + 1).strip();

		Matcher matcher = VERSION_LINE.matcher(firstLine);
		if (!matcher.matches()) {
			throw new IllegalStateException(
					"프롬프트 첫 줄은 '# version: <버전>' 형식이어야 합니다: " + path + " (첫 줄: " + firstLine + ")");
		}
		String version = matcher.group(1);
		if (version.length() > MAX_VERSION_LENGTH) {
			throw new IllegalStateException(
					"프롬프트 버전은 " + MAX_VERSION_LENGTH + "자를 넘을 수 없습니다: " + path + " (버전: " + version + ")");
		}
		if (body.isEmpty()) {
			throw new IllegalStateException("프롬프트 본문이 비어 있습니다: " + path);
		}
		return new Prompt(body, version);
	}
}
