package com.occuspec.llm;

/**
 * 多模态输入图片。
 *
 * @param mimeType 图片 MIME 类型，如 image/png、image/jpeg
 * @param base64 图片内容的 Base64 编码（不含 data URL 前缀）
 */
public record LlmImage(String mimeType, String base64) {

  /** 转为 OpenAI 兼容的 data URL。 */
  public String toDataUrl() {
    return "data:" + (mimeType == null || mimeType.isBlank() ? "image/png" : mimeType)
        + ";base64," + base64;
  }
}
