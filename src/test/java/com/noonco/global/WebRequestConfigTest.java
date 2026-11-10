package com.noonco.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.jayway.jsonpath.JsonPath;
import com.noonco.global.jwt.JwtTokenProvider;

import jakarta.persistence.EntityManagerFactory;

/**
 * 실제 Tomcat 위에 앱 전체를 띄워 CORS, multipart 크기 제한, open-in-view 설정을 확인한다.
 * MockMvc 는 Tomcat 의 multipart 크기 제한을 거치지 않으므로 업로드는 실제 HTTP 로 보낸다.
 * DB 없이 뜨도록 Hibernate 가 시작 시 DB 에 접속하지 않게 하는 속성만 덮어쓴다.
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
		"cors.allowed-origins=http://localhost:5173, https://app.example.com",
		"jwt.secret=test-secret-key-for-noonco-jwt-must-be-32-bytes-or-more"
})
@AutoConfigureMockMvc
@Import(WebRequestConfigTest.UploadTestController.class)
class WebRequestConfigTest {

	private static final int MB = 1024 * 1024;
	private static final String UPLOAD_PATH = "/api/test/upload";

	@RestController
	static class UploadTestController {

		@PostMapping(UPLOAD_PATH)
		RsData<Long> upload(@RequestParam("file") MultipartFile file) {
			return RsData.success(file.getSize());
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ApplicationContext context;

	@Autowired
	JwtTokenProvider jwtTokenProvider;

	@LocalServerPort
	int port;

	@Test
	void appStartsWithOpenInViewDisabled() {
		assertThat(context.getEnvironment().getProperty("spring.jpa.open-in-view")).isEqualTo("false");
		assertThat(context.getBeanNamesForType(EntityManagerFactory.class)).isNotEmpty();
		assertThat(context.getBeanNamesForType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
	}

	@Test
	void preflightFromAllowedOriginSucceeds() throws Exception {
		mockMvc.perform(options(UPLOAD_PATH)
						.header(HttpHeaders.ORIGIN, "https://app.example.com")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.com"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PATCH,DELETE,OPTIONS"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "Authorization"));
	}

	@Test
	void preflightFromDisallowedOriginIsRejected() throws Exception {
		mockMvc.perform(options(UPLOAD_PATH)
						.header(HttpHeaders.ORIGIN, "https://evil.example.com")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void preflightWithDisallowedMethodIsRejected() throws Exception {
		mockMvc.perform(options(UPLOAD_PATH)
						.header(HttpHeaders.ORIGIN, "http://localhost:5173")
						.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
				.andExpect(status().isForbidden());
	}

	@Test
	void uploadOfExactly5MbIsAccepted() throws Exception {
		HttpResponse<String> response = upload(5 * MB);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat((String) JsonPath.read(response.body(), "$.resultCode")).isEqualTo("OK");
		assertThat(((Number) JsonPath.read(response.body(), "$.data")).longValue()).isEqualTo(5L * MB);
	}

	@Test
	void uploadOver5MbReturnsFileTooLarge() throws Exception {
		HttpResponse<String> response = upload(5 * MB + 1);

		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE)).get().asString().startsWith("application/json");
		assertThat((String) JsonPath.read(response.body(), "$.resultCode")).isEqualTo("FILE_TOO_LARGE");
		assertThat((String) JsonPath.read(response.body(), "$.msg")).isEqualTo(ResultCode.FILE_TOO_LARGE.getMessage());
		assertThat((Object) JsonPath.read(response.body(), "$.data")).isNull();
	}

	private HttpResponse<String> upload(int fileSize) throws Exception {
		String boundary = "noonco-test-boundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\n"
				+ "Content-Disposition: form-data; name=\"file\"; filename=\"chart.png\"\r\n"
				+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(new byte[fileSize]);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + UPLOAD_PATH))
				.header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(1L))
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}
}
