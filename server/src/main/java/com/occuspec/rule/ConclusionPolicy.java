package com.occuspec.rule;

import com.occuspec.enums.Conclusion;

/**
 * 结论下限策略：规则引擎给出的是"不可下调的下限"，模型在推理后给出自己的结论，
 * 最终结论取二者中更严重者。
 *
 * <p>这样做的原因：
 * <ul>
 *   <li>规则表达式来自结构化的判定条目，可复核、无幻觉，作为安全底线；</li>
 *   <li>模型能综合条款与检查项给出更贴合个体的判断，允许其"发现问题"上调，</li>
 *       但不允许其把规则已认定的风险下调，避免漏判；</li>
 *   <li>下调被拦截时记录原因，供审计与主检医师复核时查看。</li>
 * </ul>
 */
public final class ConclusionPolicy {

  private ConclusionPolicy() {}

  /** 合并结果：最终结论、是否被下限拦截、决策来源。 */
  public record Decision(
      Conclusion conclusion, boolean overriddenByFloor, Conclusion ruleConclusion, Conclusion agentConclusion) {

    /** 决策来源：RULE 表示以下限为准，AGENT 表示采纳模型结论。 */
    public String source() {
      return overriddenByFloor ? "RULE_FLOOR" : "AGENT";
    }
  }

  /**
   * 合并规则结论与模型结论。
   *
   * @param ruleConclusion 规则命中结论（无命中时传 null）
   * @param agentConclusion 模型给出的结论（未给出时传 null）
   */
  public static Decision merge(Conclusion ruleConclusion, Conclusion agentConclusion) {
    if (agentConclusion == null) {
      Conclusion fallback = ruleConclusion == null ? Conclusion.NO_ABNORMALITY : ruleConclusion;
      return new Decision(fallback, ruleConclusion != null, ruleConclusion, null);
    }
    if (ruleConclusion == null) {
      return new Decision(agentConclusion, false, null, agentConclusion);
    }
    // 模型结论不得轻于规则下限
    if (agentConclusion.getSeverity() < ruleConclusion.getSeverity()) {
      return new Decision(ruleConclusion, true, ruleConclusion, agentConclusion);
    }
    return new Decision(agentConclusion, false, ruleConclusion, agentConclusion);
  }
}
