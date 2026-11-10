package com.noonco.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.noonco.global.ResultCode;
import com.noonco.global.RsData;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ContextConfiguration(classes = { GlobalExceptionHandlerTest.TestController.class, GlobalExceptionHandler.class })
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void successResponseUsesRsData() throws Exception {
		mockMvc.perform(get("/test/success"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resultCode").value("OK"))
				.andExpect(jsonPath("$.msg").value(ResultCode.OK.getMessage()))
				.andExpect(jsonPath("$.data.name").value("noonco"));
	}

	@Test
	void businessExceptionUsesEnumValues() throws Exception {
		mockMvc.perform(get("/test/business"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.resultCode").value("RESOURCE_NOT_FOUND"))
				.andExpect(jsonPath("$.msg").value(ResultCode.RESOURCE_NOT_FOUND.getMessage()))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	@Test
	void validationFailureReturnsFieldErrors() throws Exception {
		mockMvc.perform(post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.data[0].field").value("name"))
				.andExpect(jsonPath("$.data[0].message").value("이름을 입력해 주세요."));
	}

	@Test
	void malformedBodyReturnsInvalidRequest() throws Exception {
		mockMvc.perform(post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{broken"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("INVALID_REQUEST"));
	}

	@Test
	void unsupportedMethodReturnsMethodNotAllowed() throws Exception {
		mockMvc.perform(post("/test/success"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.resultCode").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void unknownPathReturnsNotFound() throws Exception {
		mockMvc.perform(get("/test/unknown"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.resultCode").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void unexpectedExceptionHidesInternalDetails() throws Exception {
		mockMvc.perform(get("/test/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.resultCode").value("INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("db-password"))));
	}

	@Test
	void businessExceptionIsJsonEvenWhenClientAcceptsNdjson() throws Exception {
		mockMvc.perform(get("/test/business").accept("application/x-ndjson"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.resultCode").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void unexpectedExceptionIsJsonEvenWhenClientAcceptsImage() throws Exception {
		mockMvc.perform(get("/test/unexpected").accept(MediaType.IMAGE_PNG))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.resultCode").value("INTERNAL_ERROR"));
	}

	@Test
	void validationFailureIsJsonEvenWhenClientAcceptsNdjson() throws Exception {
		mockMvc.perform(post("/test/valid").accept("application/x-ndjson")
				.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"));
	}

	@Test
	void springMvcExceptionIsJsonEvenWhenClientAcceptsNdjson() throws Exception {
		mockMvc.perform(post("/test/success").accept("application/x-ndjson"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.resultCode").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void bindingFailureHidesFrameworkMessage() throws Exception {
		mockMvc.perform(get("/test/bind").param("page", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.resultCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.data[0].field").value("page"))
				.andExpect(jsonPath("$.data[0].message").value("입력 형식이 올바르지 않습니다."))
				.andExpect(content().string(not(containsString("java.lang"))));
	}

	@Test
	void constraintFailureKeepsDeclaredMessage() throws Exception {
		mockMvc.perform(get("/test/bind").param("page", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.data[0].message").value("0 이상이어야 합니다."));
	}

	record TestRequest(@NotBlank(message = "이름을 입력해 주세요.") String name) {
	}

	record TestQuery(@Min(value = 0, message = "0 이상이어야 합니다.") Integer page) {
	}

	@RestController
	static class TestController {

		@GetMapping("/test/success")
		RsData<Map<String, String>> success() {
			return RsData.success(Map.of("name", "noonco"));
		}

		@GetMapping("/test/business")
		void business() {
			throw new BusinessException(ResultCode.RESOURCE_NOT_FOUND);
		}

		@PostMapping("/test/valid")
		void valid(@Valid @RequestBody TestRequest request) {
		}

		@GetMapping("/test/bind")
		void bind(@Valid @ModelAttribute TestQuery query) {
		}

		@GetMapping("/test/unexpected")
		void unexpected() {
			throw new IllegalStateException("db-password");
		}
	}
}
