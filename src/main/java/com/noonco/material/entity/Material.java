package com.noonco.material.entity;

import com.noonco.global.entity.BaseTimeEntity;
import com.noonco.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "material")
@SQLRestriction("deleted_at IS NULL")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Material extends BaseTimeEntity {

	// material.title 컬럼 길이
	static final int MAX_TITLE_LENGTH = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "source_type", nullable = false, length = 10)
	private SourceType sourceType;

	@Column(name = "original_filename")
	private String originalFilename;

	@Column(name = "file_path", nullable = false, length = 500)
	private String filePath;

	@Column(name = "mime_type", nullable = false, length = 100)
	private String mimeType;

	@Column(name = "file_size", nullable = false)
	private Integer fileSize;

	@Column(name = "page_count")
	private Integer pageCount;

	@Column(name = "extracted_text", columnDefinition = "MEDIUMTEXT")
	private String extractedText;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	@Column(length = 100)
	private String title;

	@Builder
	private Material(User user, SourceType sourceType, String originalFilename, String filePath,
			String mimeType, Integer fileSize, Integer pageCount, String extractedText) {
		this.user = user;
		this.sourceType = sourceType;
		this.originalFilename = originalFilename;
		this.filePath = filePath;
		this.mimeType = mimeType;
		this.fileSize = fileSize;
		this.pageCount = pageCount;
		this.extractedText = extractedText;
	}

	public void delete() {
		this.deletedAt = LocalDateTime.now();
	}

	/**
	 * 컬럼 길이(문자 수)를 넘으면 앞에서부터 100자만 남긴다. 이모지 등 서로게이트 쌍은 쪼개지 않는다.
	 */
	public void changeTitle(String title) {
		String stripped = title.strip();
		if (stripped.codePointCount(0, stripped.length()) > MAX_TITLE_LENGTH) {
			stripped = stripped.substring(0, stripped.offsetByCodePoints(0, MAX_TITLE_LENGTH)).strip();
		}
		this.title = stripped;
	}
}
