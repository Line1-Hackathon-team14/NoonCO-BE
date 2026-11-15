package com.noonco.material.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "자료 유형: IMAGE(촬영 이미지), PDF(PDF 강의자료)")
public enum SourceType {
	IMAGE, PDF
}
