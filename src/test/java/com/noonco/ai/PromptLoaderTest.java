package com.noonco.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;

import com.noonco.material.entity.SourceType;
import com.noonco.user.entity.Difficulty;

/**
 * DB 없이 PromptLoader 만 띄워서 프롬프트 파일 로딩과 시작 시 검증을 확인한다.
 */
class PromptLoaderTest {

	private static final PromptLoader LOADER = new PromptLoader(new DefaultResourceLoader());

	@TempDir
	Path dir;

	static Stream<Arguments> allCombinations() {
		return Stream.of(SourceType.values())
				.flatMap(sourceType -> Stream.of(Difficulty.values()).map(difficulty -> Arguments.of(sourceType, difficulty)));
	}

	@ParameterizedTest
	@MethodSource("allCombinations")
	void loadsPromptAndVersionForEveryCombination(SourceType sourceType, Difficulty difficulty) {
		Prompt prompt = LOADER.get(sourceType, difficulty);

		assertThat(prompt.version()).isEqualTo("v1");
		assertThat(prompt.content()).isNotBlank();
	}

	@ParameterizedTest
	@MethodSource("allCombinations")
	void contentDoesNotContainVersionLine(SourceType sourceType, Difficulty difficulty) {
		String content = LOADER.get(sourceType, difficulty).content();

		assertThat(content).doesNotContain("# version:");
		assertThat(content.lines().findFirst()).get().asString().doesNotStartWith("#");
	}

	@Test
	void eachCombinationReadsItsOwnFile() throws IOException {
		writeAllValid();
		write("pdf_hard.txt", "# version: v2-pdf-hard\npdf hard 본문");

		PromptLoader loader = loaderFor(dir);

		assertThat(loader.get(SourceType.PDF, Difficulty.HARD)).isEqualTo(new Prompt("pdf hard 본문", "v2-pdf-hard"));
		assertThat(loader.get(SourceType.IMAGE, Difficulty.EASY)).isEqualTo(new Prompt("image_easy.txt 본문", "v1"));
	}

	@Test
	void handlesCrlfAndBom() throws IOException {
		writeAllValid();
		write("image_normal.txt", "\uFEFF# version: v3\r\n첫 줄\r\n둘째 줄\r\n");

		Prompt prompt = loaderFor(dir).get(SourceType.IMAGE, Difficulty.NORMAL);

		assertThat(prompt.version()).isEqualTo("v3");
		assertThat(prompt.content()).isEqualTo("첫 줄\n둘째 줄");
	}

	@Test
	void acceptsVersionOfExactlyTwentyChars() throws IOException {
		writeAllValid();
		write("image_easy.txt", "# version: " + "a".repeat(20) + "\n본문");

		assertThat(loaderFor(dir).get(SourceType.IMAGE, Difficulty.EASY).version()).hasSize(20);
	}

	@Test
	void failsWhenVersionLongerThanTwentyChars() throws IOException {
		writeAllValid();
		write("image_easy.txt", "# version: " + "a".repeat(21) + "\n본문");

		assertThatThrownBy(() -> loaderFor(dir))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("20자")
				.hasMessageContaining("image_easy.txt");
	}

	@Test
	void failsWhenVersionLineMissing() throws IOException {
		writeAllValid();
		write("pdf_normal.txt", "본문만 있고 버전 줄이 없음\n둘째 줄");

		assertThatThrownBy(() -> loaderFor(dir))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("# version:")
				.hasMessageContaining("pdf_normal.txt");
	}

	@Test
	void failsWhenVersionLineMalformed() throws IOException {
		writeAllValid();
		write("pdf_easy.txt", "#version:v1\n본문");

		assertThatThrownBy(() -> loaderFor(dir))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("pdf_easy.txt");
	}

	@Test
	void failsWhenBodyEmpty() throws IOException {
		writeAllValid();
		write("image_hard.txt", "# version: v1\n\n");

		assertThatThrownBy(() -> loaderFor(dir))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("image_hard.txt");
	}

	@Test
	void failsWhenFileMissing() throws IOException {
		writeAllValid();
		Files.delete(dir.resolve("pdf_hard.txt"));

		assertThatThrownBy(() -> loaderFor(dir))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("pdf_hard.txt");
	}

	@Test
	void registersAsBeanAndLoadsAtStartup() {
		new ApplicationContextRunner()
				.withUserConfiguration(PromptLoader.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(PromptLoader.class).get(SourceType.IMAGE, Difficulty.EASY).version())
							.isEqualTo("v1");
				});
	}

	private PromptLoader loaderFor(Path location) {
		return new PromptLoader(new DefaultResourceLoader(), location.toUri().toString());
	}

	private void writeAllValid() throws IOException {
		for (SourceType sourceType : SourceType.values()) {
			for (Difficulty difficulty : Difficulty.values()) {
				String name = PromptLoader.fileName(sourceType, difficulty);
				write(name, "# version: v1\n" + name + " 본문\n");
			}
		}
	}

	private void write(String name, String content) throws IOException {
		Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
	}
}
