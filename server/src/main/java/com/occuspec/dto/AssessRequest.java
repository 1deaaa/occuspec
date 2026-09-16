package com.occuspec.dto;

import jakarta.validation.constraints.NotNull;

/** 判定请求。 */
public record AssessRequest(
    @NotNull Long examId,
    String ruleVersion) {}
