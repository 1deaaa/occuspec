package com.occuspec.enums;

/**
 * 四类结论枚举。定义源自 GBZ 188 第 4.8.2.2 条，写死在枚举里，禁止模型改写。
 * 结论为建议性质，必须经主检医师复核签字后生效。
 *
 * <p>{@code severity} 为严重度权重：数值越大越严重。用于比较规则结论与模型结论，
 * 保证最终结论不轻于规则下限（模型可上调、不可下调）。
 */
public enum Conclusion {
  /** 本次职业健康检查各项检查指标均在正常范围内。 */
  NO_ABNORMALITY("目前未见异常", 0),
  /** 除目标疾病之外的其他疾病或未达到目标疾病的某些检查指标的异常。 */
  OTHER_ABNORMALITY("其他疾病或异常", 1),
  /** 健康检查发现劳动者有职业禁忌证，需写明对应危害因素与疾病名称。 */
  OCCUPATIONAL_TABOO("职业禁忌证", 2),
  /** 健康检查发现劳动者可能患有职业病，应出具疑似职业病告知书并转诊。 */
  SUSPECTED_OCCUPATIONAL_DISEASE("疑似职业病", 3);

  private final String label;
  private final int severity;

  Conclusion(String label, int severity) {
    this.label = label;
    this.severity = severity;
  }

  public String getLabel() {
    return label;
  }

  public int getSeverity() {
    return severity;
  }

  /**
   * 安全解析：未知编码返回 {@link #NO_ABNORMALITY}，避免脏数据导致分支中断。
   *
   * @param code 结论编码，可带空白
   */
  public static Conclusion of(String code) {
    if (code == null || code.isBlank()) {
      return NO_ABNORMALITY;
    }
    try {
      return valueOf(code.trim());
    } catch (IllegalArgumentException e) {
      return NO_ABNORMALITY;
    }
  }

  /** 是否比另一个结论更严重（或同等）。 */
  public boolean atLeast(Conclusion other) {
    return other != null && this.severity >= other.severity;
  }

  /** 取两者中更严重的一个。 */
  public static Conclusion moreSevere(Conclusion a, Conclusion b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.severity >= b.severity ? a : b;
  }
}
