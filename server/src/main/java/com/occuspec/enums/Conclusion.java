package com.occuspec.enums;

/**
 * 四类结论枚举。定义源自 GBZ 188 第 4.8.2.2 条，写死在枚举里，禁止模型改写。
 * 结论为建议性质，必须经主检医师复核签字后生效。
 */
public enum Conclusion {
  /** 本次职业健康检查各项检查指标均在正常范围内。 */
  NO_ABNORMALITY("目前未见异常"),
  /** 除目标疾病之外的其他疾病或未达到目标疾病的某些检查指标的异常。 */
  OTHER_ABNORMALITY("其他疾病或异常"),
  /** 健康检查发现劳动者有职业禁忌证，需写明对应危害因素与疾病名称。 */
  OCCUPATIONAL_TABOO("职业禁忌证"),
  /** 健康检查发现劳动者可能患有职业病，应出具疑似职业病告知书并转诊。 */
  SUSPECTED_OCCUPATIONAL_DISEASE("疑似职业病");

  private final String label;

  Conclusion(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
}
