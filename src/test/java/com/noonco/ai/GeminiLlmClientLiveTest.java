package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.convert.DurationStyle;

/**
 * 실제 Gemini API 를 호출한다. GEMINI_API_KEY 환경변수가 있을 때만 실행되며 DB 는 필요 없다.
 * 모델·temperature·토큰 수는 GEMINI_MODEL, GEMINI_TEMPERATURE, GEMINI_MAX_OUTPUT_TOKENS 로 바꿀 수 있다.
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiLlmClientLiveTest {

	private static final String PROMPT = """
			당신은 시각장애인을 위해 시각 자료를 말로 풀어 설명하는 해설자입니다.
			첫 줄에는 {"visualType":"LINE|BAR|PIE|TABLE|OTHER","title":"자료 제목"} 형식의 JSON 한 줄만 출력하세요.
			둘째 줄부터는 자료의 구성과 핵심 수치, 추세를 한국어로 5문장 이상 설명하세요.
			""";

	private static GeminiLlmClient client;

	@BeforeAll
	static void setUp() {
		client = new GeminiLlmClient(new LlmProperties(
				"gemini",
				new LlmProperties.Gemini(
						System.getenv("GEMINI_API_KEY"),
						env("GEMINI_MODEL").orElse("gemini-3.8-flash"),
						env("GEMINI_TEMPERATURE").map(Float::valueOf).orElse(1.0f),
						env("GEMINI_MAX_OUTPUT_TOKENS").map(Integer::valueOf).orElse(4096),
						env("GEMINI_TIMEOUT").map(DurationStyle::detectAndParse).orElse(Duration.ofSeconds(120)),
						env("GEMINI_RETRY_ATTEMPTS").map(Integer::valueOf).orElse(2)),
				new LlmProperties.Fake(Duration.ZERO)));
	}

	@AfterAll
	static void tearDown() {
		client.shutdown();
	}

	@Test
	void streamsExplanationOfImageInChunks() throws IOException {
		List<String> chunks = collect(LlmRequest.ofImage(PROMPT, lineChartPng(), "image/png"));

		assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
		String full = String.join("", chunks);
		assertThat(full.lines().findFirst().orElseThrow()).contains("visualType");
		assertThat(full.lines().skip(1).toList()).isNotEmpty();
	}

	@Test
	void streamsExplanationOfExtractedText() {
		String extracted = """
				2024년 분기별 매출 (단위: 억 원)
				1분기 12.4 / 2분기 15.1 / 3분기 18.9 / 4분기 17.2
				""";
		List<String> chunks = collect(LlmRequest.ofText(PROMPT, extracted));

		assertThat(chunks).isNotEmpty();
		assertThat(String.join("", chunks)).contains("visualType");
	}

	@Test
	void closeStopsInProgressCall() throws IOException {
		List<String> received = new ArrayList<>();
		try (LlmStream stream = client.stream(LlmRequest.ofImage(PROMPT, lineChartPng(), "image/png"))) {
			for (String chunk : stream) {
				received.add(chunk);
				stream.close();
			}
			assertThat(stream.isClosed()).isTrue();
		}
		assertThat(received).hasSize(1);
	}

	private static List<String> collect(LlmRequest request) {
		List<String> chunks = new ArrayList<>();
		long startedAt = System.currentTimeMillis();
		try (LlmStream stream = client.stream(request)) {
			for (String chunk : stream) {
				chunks.add(chunk);
				System.out.printf("[chunk %d, +%dms] %s%n", chunks.size(), System.currentTimeMillis() - startedAt, chunk);
			}
		}
		return chunks;
	}

	private static Optional<String> env(String name) {
		return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
	}

	/** 1~6월 방문자 수 꺾은선 그래프 이미지를 그린다. */
	private static byte[] lineChartPng() throws IOException {
		int width = 640;
		int height = 400;
		int[] values = {1200, 1650, 2100, 2500, 2950, 3400};
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, width, height);

		g.setColor(Color.BLACK);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
		g.drawString("Monthly Visitors (Jan-Jun)", 200, 30);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
		int left = 70;
		int bottom = 350;
		int plotWidth = 520;
		int plotHeight = 280;
		g.drawLine(left, bottom, left + plotWidth, bottom);
		g.drawLine(left, bottom, left, bottom - plotHeight);
		for (int y = 0; y <= 4000; y += 1000) {
			int py = bottom - y * plotHeight / 4000;
			g.drawString(String.valueOf(y), 20, py + 5);
		}

		String[] months = {"Jan", "Feb", "Mar", "Apr", "May", "Jun"};
		int step = plotWidth / (values.length - 1);
		g.setStroke(new BasicStroke(3));
		for (int i = 0; i < values.length; i++) {
			int x = left + i * step;
			int y = bottom - values[i] * plotHeight / 4000;
			g.setColor(Color.BLACK);
			g.drawString(months[i], x - 12, bottom + 20);
			g.drawString(String.valueOf(values[i]), x - 15, y - 10);
			g.setColor(new Color(30, 100, 220));
			g.fillOval(x - 5, y - 5, 10, 10);
			if (i > 0) {
				g.drawLine(left + (i - 1) * step, bottom - values[i - 1] * plotHeight / 4000, x, y);
			}
		}
		g.dispose();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
