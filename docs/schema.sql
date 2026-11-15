-- 눈치코치(NoonCo) MVP ERD — PRD v1.4, 현재 JPA 엔티티 기준 (spring.jpa.hibernate.ddl-auto=validate)
-- ERDCloud: 가져오기 > SQL(MySQL)로 붙여넣기
-- 재분석은 실패·취소된 자료만 가능하므로 material 1건에 analysis N건(최신 1건만 COMPLETED 가능)
--
-- 기존 DB에 analysis.detail 이 없으면 아래를 먼저 실행한다.
-- ALTER TABLE `analysis` ADD COLUMN `detail` TEXT NULL COMMENT '축·수치 상세 해설(두 번 탭). 없으면 NULL' AFTER `summary`;

CREATE TABLE `users` (
  `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '사용자 ID',
  `email`         VARCHAR(255) NOT NULL COMMENT '이메일(로그인 ID, 소문자 저장)',
  `password_hash` VARCHAR(255) NOT NULL COMMENT '비밀번호 해시(BCrypt)',
  `name`          VARCHAR(50)  NOT NULL COMMENT '이름(홈·마이페이지 표시)',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입일',
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정일(JPA Auditing 갱신)',
  `deleted_at`    DATETIME     NULL COMMENT '탈퇴일(soft delete)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_USERS_EMAIL` (`email`)
) COMMENT = '사용자';

CREATE TABLE `user_settings` (
  `user_id`     BIGINT       NOT NULL COMMENT '사용자 ID',
  `difficulty`  VARCHAR(10)  NOT NULL DEFAULT 'NORMAL' COMMENT '설명 난이도: EASY/NORMAL/HARD',
  `speech_rate` DECIMAL(2,1) NOT NULL DEFAULT 1.0 COMMENT 'TTS 재생 속도(0.5~2.0)',
  `updated_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정일(JPA Auditing 갱신)',
  PRIMARY KEY (`user_id`)
) COMMENT = '사용자 설정';

CREATE TABLE `refresh_token` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '토큰 ID',
  `user_id`    BIGINT       NOT NULL COMMENT '사용자 ID',
  `token_hash` VARCHAR(255) NOT NULL COMMENT '리프레시 토큰 해시',
  `expires_at` DATETIME     NOT NULL COMMENT '만료 시각',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발급일',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_REFRESH_TOKEN_HASH` (`token_hash`)
) COMMENT = '리프레시 토큰(재발급 시 rotation)';

CREATE TABLE `material` (
  `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '자료 ID',
  `user_id`           BIGINT       NOT NULL COMMENT '사용자 ID',
  `source_type`       VARCHAR(10)  NOT NULL COMMENT '자료 유형: IMAGE/PDF',
  `original_filename` VARCHAR(255) NULL COMMENT '원본 파일명(촬영 이미지는 NULL일 수 있음)',
  `file_path`         VARCHAR(500) NOT NULL COMMENT '저장소 키(상대 경로, UUID 파일명)',
  `mime_type`         VARCHAR(100) NOT NULL COMMENT 'image/jpeg, image/png, application/pdf',
  `file_size`         INT          NOT NULL COMMENT '바이트(최대 5MB)',
  `page_count`        INT          NULL COMMENT 'PDF 쪽수(최대 30, IMAGE는 NULL)',
  `title`             VARCHAR(100) NULL COMMENT 'AI 생성 제목(스트림 meta.title, 해설 완료 시 저장)',
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '촬영·업로드일(학습 기록 정렬)',
  `deleted_at`        DATETIME     NULL COMMENT '삭제일(soft delete, 파일은 유지)',
  PRIMARY KEY (`id`)
) COMMENT = '자료(촬영 이미지·PDF 원본)';

CREATE TABLE `analysis` (
  `id`             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '분석 ID',
  `material_id`    BIGINT      NOT NULL COMMENT '자료 ID',
  `difficulty`     VARCHAR(10) NOT NULL COMMENT '분석 당시 난이도: EASY/NORMAL/HARD',
  `visual_type`    VARCHAR(10) NULL COMMENT '시각 자료 유형: LINE/BAR/PIE/TABLE/OTHER (PDF는 NULL)',
  `summary`        TEXT        NULL COMMENT '요약 해설(한 번 탭: 개요·구조·핵심 의미)',
  `detail`         TEXT        NULL COMMENT '축·수치 상세 해설(두 번 탭). 없으면 NULL',
  `status`         VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '상태: PENDING/COMPLETED/FAILED/UNREADABLE',
  `model`          VARCHAR(50) NULL COMMENT '사용한 AI 모델',
  `prompt_version` VARCHAR(20) NULL COMMENT '프롬프트 버전',
  `created_at`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '분석 요청일',
  PRIMARY KEY (`id`)
) COMMENT = '분석(해설 이력)';

ALTER TABLE `user_settings`
  ADD CONSTRAINT `FK_users_TO_user_settings_1`
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `refresh_token`
  ADD CONSTRAINT `FK_users_TO_refresh_token_1`
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `material`
  ADD CONSTRAINT `FK_users_TO_material_1`
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`);

ALTER TABLE `analysis`
  ADD CONSTRAINT `FK_material_TO_analysis_1`
  FOREIGN KEY (`material_id`) REFERENCES `material` (`id`);
