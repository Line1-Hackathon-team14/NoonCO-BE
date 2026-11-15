package com.noonco.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.noonco.global.ResultCode;
import com.noonco.user.dto.UpdateSettingsRequest;
import com.noonco.user.dto.UserResponse;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(UserService.class)
class UserServiceTest {

	@Autowired UserService userService;
	@Autowired UserRepository userRepository;
	@Autowired UserSettingsRepository userSettingsRepository;
	@Autowired EntityManager entityManager;

	private Long userId;

	@BeforeEach
	void setUp() {
		// 로컬 DB 에 있을 수 있는 데이터와 겹치지 않는 이메일
		User user = userRepository.save(User.builder()
				.name("김눈치")
				.email("user-test-" + UUID.randomUUID() + "@example.com")
				.passwordHash("hash")
				.build());
		userSettingsRepository.save(new UserSettings(user));
		userId = user.getId();
	}

	@Test
	void getMeReturnsDefaultSettings() {
		UserResponse me = userService.getMe(userId);

		assertThat(me.name()).isEqualTo("김눈치");
		assertThat(me.difficulty()).isEqualTo(Difficulty.NORMAL);
		assertThat(me.speechRate()).isEqualByComparingTo("1.0");
	}

	@Test
	void updateSettingsChangesOnlySentFieldsAndPersists() {
		userService.updateSettings(userId, new UpdateSettingsRequest(Difficulty.HARD, null));
		flushAndClear();

		UserResponse afterDifficulty = userService.getMe(userId);
		assertThat(afterDifficulty.difficulty()).isEqualTo(Difficulty.HARD);
		assertThat(afterDifficulty.speechRate()).isEqualByComparingTo("1.0");

		userService.updateSettings(userId, new UpdateSettingsRequest(null, new BigDecimal("1.2")));
		flushAndClear();

		UserResponse afterSpeechRate = userService.getMe(userId);
		assertThat(afterSpeechRate.difficulty()).isEqualTo(Difficulty.HARD);
		assertThat(afterSpeechRate.speechRate()).isEqualByComparingTo("1.2");
	}

	@Test
	void unknownUserReturnsNotFound() {
		assertThatThrownBy(() -> userService.getMe(-1L))
				.extracting("resultCode").isEqualTo(ResultCode.RESOURCE_NOT_FOUND);
		assertThatThrownBy(() -> userService.updateSettings(-1L, new UpdateSettingsRequest(Difficulty.EASY, null)))
				.extracting("resultCode").isEqualTo(ResultCode.RESOURCE_NOT_FOUND);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}
}
