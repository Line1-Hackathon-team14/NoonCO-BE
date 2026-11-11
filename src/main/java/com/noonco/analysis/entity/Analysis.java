package com.noonco.analysis.entity;

import com.noonco.global.entity.BaseTimeEntity;
import com.noonco.material.entity.Material;
import com.noonco.user.entity.Difficulty;
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
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "analysis")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Analysis extends BaseTimeEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "material_id", nullable = false)
	private Material material;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 10)
	private Difficulty difficulty;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "visual_type", length = 10)
	private VisualType visualType;

	@Column(columnDefinition = "TEXT")
	private String summary;

	// 축·수치 상세 해설. 모델 출력에 상세 구간이 없으면 null
	@Column(columnDefinition = "TEXT")
	private String detail;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private AnalysisStatus status = AnalysisStatus.PENDING;

	@Column(length = 50)
	private String model;

	@Column(name = "prompt_version", length = 20)
	private String promptVersion;

	public Analysis(Material material, Difficulty difficulty) {
		this.material = material;
		this.difficulty = difficulty;
	}

	public void complete(VisualType visualType, String summary, String detail, String model, String promptVersion) {
		this.visualType = visualType;
		this.summary = summary;
		this.detail = detail;
		this.model = model;
		this.promptVersion = promptVersion;
		this.status = AnalysisStatus.COMPLETED;
	}

	public void fail() {
		this.status = AnalysisStatus.FAILED;
	}

	/**
	 * 서버가 중간에 내려가 PENDING 으로 남은 분석은 제한 시간이 지나면 진행 중으로 보지 않는다.
	 * 해설 시작과 자료 삭제가 같은 기준을 쓴다.
	 */
	public boolean isInProgress(Duration timeout) {
		return status == AnalysisStatus.PENDING && getCreatedAt().plus(timeout).isAfter(LocalDateTime.now());
	}
}
