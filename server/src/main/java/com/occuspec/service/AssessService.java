package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.dto.AssessResultView;
import com.occuspec.entity.Assessment;
import com.occuspec.entity.Evidence;
import com.occuspec.entity.Recommendation;
import com.occuspec.entity.Rule;
import com.occuspec.mapper.AssessmentMapper;
import com.occuspec.mapper.EvidenceMapper;
import com.occuspec.mapper.ExamItemMapper;
import com.occuspec.mapper.RecommendationMapper;
import com.occuspec.mapper.RuleMapper;
import com.occuspec.rule.RuleEvaluator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 判定编排深模块：小接口 assess(examId) 隐藏检索、规则、模型、落库全部细节。
 *
 * <p>判定由 Agent 驱动（见 {@link AssessAgentService}）：模型自主决定检索什么、过滤维度与轮次；
 * 规则引擎结论作为不可下调的下限，由 Agent 层强制合并。
 * 本类只负责：装配输入（快照→事实）、调用 Agent、事务落库（评估+证据+推荐）。
 */
@Service
public class AssessService {
  private static final Logger log = LoggerFactory.getLogger(AssessService.class);
  private final ExamService examService;
  private final AssessAgentService agentService;
  private final RuleMapper ruleMapper;
  private final AssessmentMapper assessmentMapper;
  private final EvidenceMapper evidenceMapper;
  private final ExamItemMapper examItemMapper;
  private final RecommendationMapper recommendationMapper;
  private final AuditService auditService;
  private final HazardService hazardService;
  private final FieldMappingService fieldMappingService;
  private final ObjectMapper objectMapper;

  public AssessService(
      ExamService examService,
      AssessAgentService agentService,
      RuleMapper ruleMapper,
      AssessmentMapper assessmentMapper,
      EvidenceMapper evidenceMapper,
      ExamItemMapper examItemMapper,
      RecommendationMapper recommendationMapper,
      AuditService auditService,
      HazardService hazardService,
      FieldMappingService fieldMappingService,
      ObjectMapper objectMapper) {
    this.examService = examService;
    this.agentService = agentService;
    this.ruleMapper = ruleMapper;
    this.assessmentMapper = assessmentMapper;
    this.evidenceMapper = evidenceMapper;
    this.examItemMapper = examItemMapper;
    this.recommendationMapper = recommendationMapper;
    this.auditService = auditService;
    this.hazardService = hazardService;
    this.fieldMappingService = fieldMappingService;
    this.objectMapper = objectMapper;
  }

  /** 判定进度回调：供 SSE 端点推送 reasoning/tool_call/tool_result 事件。 */
  public interface ProgressListener {
    void onReasoning(String text);
    void onToolCall(String tool, Map<String, Object> args);
    void onToolResult(String tool, int hits, String note);
  }

  /** 判定：装配输入 → Agent 自主判定 → 事务落库。 */
  @Transactional
  public AssessResultView assess(Long examId, String ruleVersion, ProgressListener listener) {
    long start = System.currentTimeMillis();
    var snapshot = examService.snapshot(examId);
    String hazard = snapshot.exam().getHazardCode();
    Map<String, Object> facts = buildFacts(snapshot);
    emit(listener, "开始判定：危害因素 " + hazardName(hazard) + "，检查项 " + facts.size() + " 项");

    // 规则下限：按危害因素取启用规则，交由 Agent 合并
    var rules = loadRules(hazard, ruleVersion);
    var specs = rules.stream()
        .map(r -> new RuleEvaluator.RuleSpec(r.getCode(), r.getExpression(), r.getConclusion(),
            r.getWeight() == null ? 0 : r.getWeight()))
        .toList();

    var input = new AssessAgentService.AssessInput(
        hazard, hazardName(hazard), facts, specs, buildExamSummary(snapshot, facts));
    var result = agentService.assess(input, new AssessAgentService.AgentProgress() {
      @Override
      public void onReasoning(String text) {
        emit(listener, text);
      }

      @Override
      public void onToolCall(String tool, Map<String, Object> args) {
        if (listener != null) {
          listener.onToolCall(tool, args);
        }
      }

      @Override
      public void onToolResult(String tool, int hits, String note) {
        if (listener != null) {
          listener.onToolResult(tool, hits, note);
        }
      }
    });

    emit(listener, "落库保存证据链");
    // 事务落库：评估 + 证据 + 推荐
    List<Map<String, Object>> toolCalls = new ArrayList<>();
    for (var t : result.traces()) {
      Map<String, Object> map = new HashMap<>();
      map.put("tool", t.tool());
      map.put("args", t.args());
      map.put("hits", t.hits());
      map.put("note", t.note());
      toolCalls.add(map);
    }
    String matchedRule = result.floorApplied() ? "RULE_FLOOR" : "AGENT";
    AssessResultView view = persist(examId, snapshot, result, toolCalls, matchedRule, start);
    auditService.record("ASSESSMENT", String.valueOf(view.assessmentId()), snapshot.exam().getOperatorId(),
        "提交判定", result.conclusion().name(), System.currentTimeMillis() - start);
    return view;
  }

  /** 检查项事实摘要：拼成文本供模型阅读。 */
  private String buildExamSummary(ExamService.ExamSnapshot snapshot, Map<String, Object> facts) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (var item : snapshot.items()) {
      if (i++ > 0) {
        sb.append("；");
      }
      sb.append(item.getItemName() == null || item.getItemName().isBlank()
          ? item.getItemCode() : item.getItemName());
      sb.append("=");
      if (item.getValueNum() != null) {
        sb.append(item.getValueNum().stripTrailingZeros().toPlainString());
      } else {
        sb.append(item.getValueText() == null ? "" : item.getValueText());
      }
      if (item.getUnit() != null && !item.getUnit().isBlank()) {
        sb.append(" ").append(item.getUnit());
      }
    }
    return sb.toString();
  }

  private Map<String, Object> buildFacts(ExamService.ExamSnapshot snapshot) {
    Map<String, Object> facts = new LinkedHashMap<>();
    for (var item : snapshot.items()) {
      if (item.getValueNum() != null) {
        facts.put(item.getItemCode(), item.getValueNum().doubleValue());
      } else if (item.getValueText() != null && !item.getValueText().isBlank()) {
        facts.put(item.getItemCode(), item.getValueText());
      }
    }
    // 字段归一化：外部报告字段名可能是中文名或机构自定义列名，
    // 经别名映射补出规范 fact 编码键，规则表达式才能稳定命中。
    return fieldMappingService.canonicalizeFacts(facts);
  }

  /** 危害中文名：查库获取，新增危害因素无需改代码。 */
  private String hazardName(String hazardCode) {
    return hazardService.nameOf(hazardCode);
  }

  private List<Rule> loadRules(String hazard, String ruleVersion) {
    var wrapper = new LambdaQueryWrapper<Rule>().eq(Rule::getEnabled, true);
    if (hazard != null && !hazard.isBlank()) {
      wrapper.and(w -> w.eq(Rule::getHazardCode, hazard).or().eq(Rule::getHazardCode, ""));
    }
    if (ruleVersion != null && !ruleVersion.isBlank()) {
      wrapper.eq(Rule::getVersion, ruleVersion);
    }
    wrapper.orderByDesc(Rule::getWeight);
    return ruleMapper.selectList(wrapper);
  }

  private AssessResultView persist(Long examId, ExamService.ExamSnapshot snapshot,
      AssessAgentService.AgentResult result, List<Map<String, Object>> toolCalls,
      String matchedRule, long start) {
    // 输入快照：可复现
    String snapshotJson;
    try {
      snapshotJson = objectMapper.writeValueAsString(Map.of(
          "examId", examId, "hazard", snapshot.exam().getHazardCode(),
          "items", snapshot.items().stream().map(i -> Map.of(
              "code", i.getItemCode(),
              "num", i.getValueNum() == null ? "" : i.getValueNum().toString(),
              "text", i.getValueText() == null ? "" : i.getValueText())).toList()));
    } catch (Exception e) {
      snapshotJson = "{}";
    }
    Assessment assessment = new Assessment();
    assessment.setExamId(examId);
    assessment.setInputSnapshot(snapshotJson);
    assessment.setConclusion(result.conclusion().name());
    // 结论来源：AGENT（模型提交）/ RULE_FLOOR（模型被下限拦截）/ RULE_FALLBACK（模型不可用）
    assessment.setConclusionSource(result.decisionSource());
    assessment.setRuleVersion(matchedRule);
    // Agent 决策轨迹：轮次、工具序列、推理摘要与依据，供审计回放
    assessment.setAgentRounds(result.rounds());
    assessment.setAgentTraces(toJson(toolCalls));
    assessment.setAgentReasoning(head(result.reasoning(), 8000));
    assessment.setRationale(head(result.rationale(), 4000));
    assessment.setReviewStatus("PENDING");
    assessment.setPromptTokens(result.usage().promptTokens());
    assessment.setCompletionTokens(result.usage().completionTokens());
    assessment.setTotalTokens(result.usage().totalTokens());
    assessment.setCostMs(System.currentTimeMillis() - start);
    assessmentMapper.insert(assessment);

    // 证据链：优先落模型引用的条款；模型未引用时回退到检索工具召回的前若干条
    List<AssessAgentService.CitedClause> cited = result.citations();
    List<AssessResultView.EvidenceView> evidenceViews = new ArrayList<>();
    for (var c : cited) {
      Evidence evidence = new Evidence();
      evidence.setAssessmentId(assessment.getId());
      evidence.setClauseId(c.clauseId());
      evidence.setStandardCode(c.standardCode());
      evidence.setClauseNo(c.clauseNo());
      evidence.setQuote(head(c.quote(), 1500));
      evidence.setItemCode("");
      evidence.setReason("Agent 引用（" + c.source() + "）");
      evidenceMapper.insert(evidence);
      evidenceViews.add(new AssessResultView.EvidenceView(c.standardCode(), c.clauseNo(),
          head(c.quote(), 600), "", evidence.getReason(), c.pageNo()));
    }

    // 推荐：标准内推荐（来自引用条款）与机构扩展分开
    List<AssessResultView.RecommendationView> recViews = new ArrayList<>();
    Recommendation std = new Recommendation();
    std.setAssessmentId(assessment.getId());
    std.setItemCode("FOLLOWUP");
    std.setItemName("按条款要求复查");
    std.setReason("来自适用条款的检查要求");
    std.setExtended(false);
    std.setSourceClauseNo(cited.isEmpty() ? "" : cited.get(0).clauseNo());
    recommendationMapper.insert(std);
    recViews.add(new AssessResultView.RecommendationView(std.getItemCode(), std.getItemName(),
        std.getReason(), false, std.getSourceClauseNo()));

    return new AssessResultView(assessment.getId(), examId, result.conclusion().name(),
        result.conclusion().getLabel(), assessment.getConclusionSource(), evidenceViews, recViews,
        toolCalls, assessment.getPromptTokens(), assessment.getCompletionTokens(),
        assessment.getTotalTokens(), assessment.getCostMs(), result.rounds(),
        result.floorApplied(), result.rationale());
  }

  private void emit(ProgressListener listener, String text) {
    if (listener != null) {
      listener.onReasoning(text);
    }
  }

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }

  private String toJson(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      return null;
    }
  }
}
