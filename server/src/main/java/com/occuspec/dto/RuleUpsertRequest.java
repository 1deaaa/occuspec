package com.occuspec.dto;

import jakarta.validation.constraints.NotBlank;

/** 规则创建与更新请求。 */
public record RuleUpsertRequest(
    @NotBlank String code,
    @NotBlank String name,
    String hazardCode,
    @NotBlank String expression,
    @NotBlank String conclusion,
    Integer weight,
    String version) {}
