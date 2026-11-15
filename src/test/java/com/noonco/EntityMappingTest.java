package com.noonco;

import static org.assertj.core.api.Assertions.assertThat;

import com.noonco.analysis.entity.Analysis;
import com.noonco.analysis.entity.AnalysisStatus;
import com.noonco.analysis.repository.AnalysisRepository;
import com.noonco.auth.entity.RefreshToken;
import com.noonco.auth.repository.RefreshTokenRepository;
import com.noonco.material.entity.Material;
import com.noonco.material.entity.SourceType;
import com.noonco.material.repository.MaterialRepository;
import com.noonco.user.entity.Difficulty;
import com.noonco.user.entity.User;
import com.noonco.user.entity.UserSettings;
import com.noonco.user.repository.UserRepository;
import com.noonco.user.repository.UserSettingsRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EntityMappingTest {

	@Autowired UserRepository userRepository;
	@Autowired UserSettingsRepository userSettingsRepository;
	@Autowired RefreshTokenRepository refreshTokenRepository;
	@Autowired MaterialRepository materialRepository;
	@Autowired AnalysisRepository analysisRepository;
	@Autowired EntityManager entityManager;

	@Test
	void savesAndFindsAllEntities() {
		User user = saveUser();
		Material material = saveMaterial(user);
		UserSettings settings = userSettingsRepository.save(new UserSettings(user));
		RefreshToken token = refreshTokenRepository.save(RefreshToken.builder()
				.user(user)
				.tokenHash("entity-mapping-test-hash")
				.expiresAt(LocalDateTime.now().plusDays(14))
				.build());
		Analysis analysis = analysisRepository.save(new Analysis(material, Difficulty.NORMAL));
		flushAndClear();

		User foundUser = userRepository.findById(user.getId()).orElseThrow();
		assertThat(foundUser.getCreatedAt()).isNotNull();
		assertThat(foundUser.getUpdatedAt()).isNotNull();

		UserSettings foundSettings = userSettingsRepository.findById(settings.getUserId()).orElseThrow();
		assertThat(foundSettings.getDifficulty()).isEqualTo(Difficulty.NORMAL);
		assertThat(foundSettings.getSpeechRate()).isEqualByComparingTo("1.0");
		assertThat(foundSettings.getUpdatedAt()).isNotNull();

		assertThat(refreshTokenRepository.findById(token.getId())).isPresent();
		assertThat(materialRepository.findById(material.getId()).orElseThrow().getCreatedAt()).isNotNull();

		Analysis foundAnalysis = analysisRepository.findById(analysis.getId()).orElseThrow();
		assertThat(foundAnalysis.getStatus()).isEqualTo(AnalysisStatus.PENDING);
		assertThat(foundAnalysis.getMaterial().getId()).isEqualTo(material.getId());
	}

	@Test
	void softDeletedMaterialIsHiddenFromFind() {
		Material material = saveMaterial(saveUser());
		material.delete();
		flushAndClear();

		assertThat(materialRepository.findById(material.getId())).isEmpty();
	}

	@Test
	void softDeletedUserIsHiddenFromFind() {
		User user = saveUser();
		flushAndClear();
		entityManager.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
				.setParameter("id", user.getId())
				.executeUpdate();
		entityManager.clear();

		assertThat(userRepository.findById(user.getId())).isEmpty();
	}

	private User saveUser() {
		return userRepository.save(User.builder()
				.email("00000000000")
				.passwordHash("hash")
				.name("테스트")
				.build());
	}

	private Material saveMaterial(User user) {
		return materialRepository.save(Material.builder()
				.user(user)
				.sourceType(SourceType.IMAGE)
				.filePath("test/uuid.jpg")
				.mimeType("image/jpeg")
				.fileSize(1024)
				.build());
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}
}
