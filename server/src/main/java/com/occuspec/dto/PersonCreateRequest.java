package com.occuspec.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/** 体检对象创建请求。 */
public record PersonCreateRequest(
    @NotBlank String name,
    String idCard,
    String gender,
    LocalDate birthDate,
    String company,
    String jobType,
    String exposureHistory) {}
