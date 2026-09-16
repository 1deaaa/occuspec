package com.occuspec.llm;

import java.util.Map;

/** 工具声明：名称、说明与 JSON Schema 参数。 */
public record LlmToolSpec(String name, String description, Map<String, Object> parameters) {}
