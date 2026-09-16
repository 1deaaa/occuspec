package com.occuspec.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.occuspec.enums.Conclusion;
import org.junit.jupiter.api.Test;

/**
 * 结论下限策略验证：模型可上调不可下调，任一侧缺失时回退。
 */
class ConclusionPolicyTest {

  @Test
  void 模型上调结论被采纳() {
    var d = ConclusionPolicy.merge(Conclusion.OTHER_ABNORMALITY, Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE);
    assertEquals(Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE, d.conclusion());
    assertFalse(d.overriddenByFloor());
    assertEquals("AGENT", d.source());
  }

  @Test
  void 模型下调结论被下限拦截() {
    var d = ConclusionPolicy.merge(Conclusion.OCCUPATIONAL_TABOO, Conclusion.NO_ABNORMALITY);
    assertEquals(Conclusion.OCCUPATIONAL_TABOO, d.conclusion(), "不得轻于规则下限");
    assertTrue(d.overriddenByFloor());
    assertEquals("RULE_FLOOR", d.source());
  }

  @Test
  void 模型给出同级结论则采纳模型来源() {
    var d = ConclusionPolicy.merge(Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE,
        Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE);
    assertEquals(Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE, d.conclusion());
    assertFalse(d.overriddenByFloor());
  }

  @Test
  void 模型未给结论时以下限为准() {
    var d = ConclusionPolicy.merge(Conclusion.OCCUPATIONAL_TABOO, null);
    assertEquals(Conclusion.OCCUPATIONAL_TABOO, d.conclusion());
    assertEquals("RULE_FLOOR", d.source());
  }

  @Test
  void 两侧都缺失时回退未见异常() {
    var d = ConclusionPolicy.merge(null, null);
    assertEquals(Conclusion.NO_ABNORMALITY, d.conclusion());
    assertEquals("AGENT", d.source());
  }

  @Test
  void 严重度顺序正确() {
    assertTrue(Conclusion.SUSPECTED_OCCUPATIONAL_DISEASE.getSeverity()
        > Conclusion.OCCUPATIONAL_TABOO.getSeverity());
    assertTrue(Conclusion.OCCUPATIONAL_TABOO.getSeverity()
        > Conclusion.OTHER_ABNORMALITY.getSeverity());
    assertTrue(Conclusion.OTHER_ABNORMALITY.getSeverity()
        > Conclusion.NO_ABNORMALITY.getSeverity());
  }

  @Test
  void 未知编码安全回退() {
    assertEquals(Conclusion.NO_ABNORMALITY, Conclusion.of("NOT_A_CONCLUSION"));
    assertEquals(Conclusion.OCCUPATIONAL_TABOO, Conclusion.of(" OCCUPATIONAL_TABOO "));
    assertEquals(Conclusion.NO_ABNORMALITY, Conclusion.of(null));
  }
}
