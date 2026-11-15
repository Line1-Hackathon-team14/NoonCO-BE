package com.noonco.material.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;

import com.jayway.jsonpath.JsonPath;
import com.noonco.global.ResultCode;
import com.noonco.global.jwt.JwtTokenProvider;

/**
 * MockMvc 는 Tomcat 의 multipart 크기 제한을 거치지 않으므로, 실제 업로드 API 에 HTTP 로 보내 5MB 초과를 확인한다.
 * 요청이 컨트롤러에 닿기 전에 거절되므로 DB 없이 뜨도록 WebRequestConfigTest 와 같은 속성을 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:mysql://localhost:1/noonco",
		"spring.datasource.username=test",
		"spring.datasource.password=test",
		"spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false",
		"spring.sql.init.mode=never",
		"llm.provider=fake",
		"jwt.secret=test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more"
})
class MaterialUploadSizeTest {

	private static final int MB = 1024 * 1024;

	@Autowired
	JwtTokenProvider jwtTokenProvider;

	@LocalServerPort
	int port;

	@Test
	void imageOver5MbReturnsFileTooLarge() throws Exception {
		assertFileTooLarge(upload("images", "chart.png", "image/png", 5 * MB + 1));
	}

	@Test
	void pdfOver5MbReturnsFileTooLarge() throws Exception {
		assertFileTooLarge(upload("pdfs", "lecture.pdf", "application/pdf", 5 * MB + 1));
	}

	private static void assertFileTooLarge(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(413);
		assertThat((String) JsonPath.read(response.body(), "$.resultCode")).isEqualTo("FILE_TOO_LARGE");
		assertThat((String) JsonPath.read(response.body(), "$.msg")).isEqualTo(ResultCode.FILE_TOO_LARGE.getMessage());
		assertThat((Object) JsonPath.read(response.body(), "$.data")).isNull();
	}

	private HttpResponse<String> upload(String path, String filename, String contentType, int fileSize)
			throws Exception {
		String boundary = "noonco-test-boundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
				+ "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(new byte[fileSize]);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/materials/" + path))
				.header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(1L))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}
}
