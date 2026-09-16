package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.dto.AssessResultView;
import com.occuspec.entity.Assessment;
import com.occuspec.entity.Evidence;
import com.occuspec.entity.Recommendation;
import com.occuspec.entity.Rule;
import com.occuspec.enums.Conclusion;
import com.occuspec.llm.LlmGateway;
import com.occuspec.llm.LlmResult;
import com.occuspec.mapper.AssessmentMapper;
import com.occuspec.mapper.EvidenceMapper;
import com.occuspec.mapper.ExamItemMapper;
import com.occuspec.mapper.RecommendationMapper;
import com.occuspec.mapper.RuleMapper;
import com.occuspec.rag.ClauseRetrievalTools;
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
 * 规则优先于模型输出；模型故障降级为纯规则结论，失败不抛穿。
 */
@Service
public class AssessService {
  private static final Logger log = LoggerFactory.getLogger(AssessService.class);
  private final ExamService examService;
  private final ClauseRetrievalTools retrievalTools;
  private final RuleMapper ruleMapper;
  private final RuleEvaluator ruleEvaluator;
  private final LlmGateway llmGateway;
  private final AssessmentMapper assessmentMapper;
  private final EvidenceMapper evidenceMapper;
  private final ExamItemMapper examItemMapper;
  private final RecommendationMapper recommendationMapper;
  private final AuditService auditService;
  private final ObjectMapper objectMapper;

  public AssessService(
      ExamService examService,
      ClauseRetrievalTools retrievalTools,
      RuleMapper ruleMapper,
      LlmGateway llmGateway,
      AssessmentMapper assessmentMapper,
      EvidenceMapper evidenceMapper,
      ExamItemMapper examItemMapper,
      RecommendationMapper recommendationMapper,
      AuditService auditService,
      ObjectMapper objectMapper) {
    this.examService = examService;
    this.retrievalTools = retrievalTools;
    this.ruleMapper = ruleMapper;
    this.ruleEvaluator = new RuleEvaluator();
    this.llmGateway = llmGateway;
    this.assessmentMapper = assessmentMapper;
    this.evidenceMapper = evidenceMapper;
    this.examItemMapper = examItemMapper;
    this.recommendationMapper = recommendationMapper;
    this.auditService = auditService;
    this.objectMapper = objectMapper;
  }

  /** 判定进度回调：供 SSE 端点推送 reasoning/tool_call/tool_result 事件。 */
  public interface ProgressListener {
    void onReasoning(String text);
    void onToolCall(String tool, Map<String, Object> args);
    void onToolResult(String tool, int hits, String note);
  }

  /** 同步判定：检索 → 规则 → 模型渲染 → 事务落库。 */
  @Transactional
  public AssessResultView assess(Long examId, String ruleVersion, ProgressListener listener) {
    long start = System.currentTimeMillis();
    var snapshot = examService.snapshot(examId);
    String hazard = snapshot.exam().getHazardCode();
    Map<String, Object> facts = buildFacts(snapshot);
    List<Map<String, Object>> toolCalls = new ArrayList<>();

    emit(listener, "开始判定：危害因素 " + hazard + "，检查项 " + facts.size() + " 项");

    // 1. 危害路由：适用标准与节级条款
    var route = retrievalTools.route(hazard);
    toolCalls.add(toMap(route.call()));
    emitTool(listener, route.call());

    // 2. 语义检索：优先危害因素过滤；向量库覆盖不足时自动回退关键词直查（工具内已实现二次放宽）。
    // 查询文本使用中文危害名+检查项中文名，避免英文编码在中文向量空间失配。
    String query = hazardName(hazard) + " 职业禁忌证 目标疾病 检查内容 " + String.join(" ", itemNames(snapshot));
    var retrieval = retrievalTools.retrieve(query, 8, hazard, null);
    toolCalls.add(toMap(retrieval.call()));
    emitTool(listener, retrieval.call());
    emit(listener, "召回条款 " + retrieval.clauses().size() + " 条，开始规则匹配");

    // 3. 规则匹配：已按权重排序，首个命中即结论
    var rules = loadRules(hazard, ruleVersion);
    var specs = rules.stream()
        .map(r -> new RuleEvaluator.RuleSpec(r.getCode(), r.getExpression(), r.getConclusion(),
            r.getWeight() == null ? 0 : r.getWeight()))
        .toList();
    var match = ruleEvaluator.firstMatch(specs, facts);
    String conclusion = match == null ? Conclusion.NO_ABNORMALITY.name() : match.conclusion();
    String matchedRule = match == null ? "DEFAULT" : match.ruleCode();
    emit(listener, "规则命中：" + matchedRule + "，建议结论 " + labelOf(conclusion));

    // 4. 模型渲染判定依据（失败降级为空，不抛穿）
    LlmResult rendered = renderBasis(hazard, facts, retrieval.clauses(), conclusion, listener);
    emit(listener, "依据渲染完成，落库保存证据链");

    // 5. 事务落库：评估 + 证据 + 推荐
    AssessResultView view = persist(examId, snapshot, conclusion, matchedRule, retrieval,
        rendered, toolCalls, start);
    auditService.record("ASSESSMENT", String.valueOf(view.assessmentId()), snapshot.exam().getOperatorId(),
        "提交判定", conclusion, System.currentTimeMillis() - start);
    return view;
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
    return facts;
  }

  /** 危害中文名：向量查询用中文，避免英文编码在中文向量空间失配。 */
  private String hazardName(String hazardCode) {
    if (hazardCode == null) {
      return "";
    }
    return switch (hazardCode) {
      case "noise" -> "噪声";
      case "lead" -> "铅及其无机化合物";
      case "benzene" -> "苯";
      case "dust_silica" -> "游离二氧化硅粉尘";
      case "toluene" -> "甲苯";
      default -> hazardCode;
    };
  }

  /** 检查项中文名列表：拼入向量查询文本。 */
  private List<String> itemNames(ExamService.ExamSnapshot snapshot) {
    List<String> names = new ArrayList<>();
    for (var item : snapshot.items()) {
      if (item.getItemName() != null && !item.getItemName().isBlank()) {
        names.add(item.getItemName());
      } else {
        names.add(item.getItemCode());
      }
    }
    return names;
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

  private LlmResult renderBasis(String hazard, Map<String, Object> facts,
      List<ClauseRetrievalTools.RetrievedClause> clauses, String conclusion, ProgressListener listener) {
    try {
      StringBuilder prompt = new StringBuilder();
      prompt.append("你是职业健康检查辅助判定助手，只做建议性表述，不得使用确诊措辞。\n");
      prompt.append("危害因素：").append(hazardName(hazard)).append("\n检查项：").append(facts).append("\n");
      prompt.append("适用条款：\n");
      for (var c : clauses.subList(0, Math.min(5, clauses.size()))) {
        prompt.append("- ").append(c.standardCode()).append(" ").append(c.clauseNo())
            .append(" ").append(c.title()).append("（第 ").append(c.pageNo()).append(" 页）\n");
      }
      prompt.append("规则建议结论：").append(labelOf(conclusion)).append("\n");
      prompt.append("请用 3 句话说明判定依据，每句引用条款编号。");
      StringBuilder buf = new StringBuilder();
      final long[] usage = new long[3];
      llmGateway.stream(prompt.toString(), buf::append, thinking -> {
        if (listener != null) {
          listener.onReasoning(thinking);
        }
      }, u -> {
        usage[0] = u.promptTokens();
        usage[1] = u.completionTokens();
        usage[2] = u.totalTokens();
      });
      String text = buf.toString();
      return new com.occuspec.llm.LlmResult(text,
          new com.occuspec.llm.LlmUsage(usage[0], usage[1], usage[2]), false, null);
    } catch (Exception ex) {
      log.warn("依据渲染降级 err={}", ex.getMessage());
      return com.occuspec.llm.LlmResult.degraded("");
    }
  }

  private AssessResultView persist(Long examId, ExamService.ExamSnapshot snapshot, String conclusion,
      String matchedRule, ClauseRetrievalTools.RetrievalResult retrieval, LlmResult rendered,
      List<Map<String, Object>> toolCalls, long start) {
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
    assessment.setConclusion(conclusion);
    assessment.setConclusionSource(rendered.degraded() ? "RULE" : "LLM");
    assessment.setRuleVersion(matchedRule);
    assessment.setReviewStatus("PENDING");
    assessment.setPromptTokens(rendered.usage().promptTokens());
    assessment.setCompletionTokens(rendered.usage().completionTokens());
    assessment.setTotalTokens(rendered.usage().totalTokens());
    assessment.setCostMs(System.currentTimeMillis() - start);
    assessmentMapper.insert(assessment);

    List<AssessResultView.EvidenceView> evidenceViews = new ArrayList<>();
    for (var c : retrieval.clauses().subList(0, Math.min(5, retrieval.clauses().size()))) {
      Evidence evidence = new Evidence();
      evidence.setAssessmentId(assessment.getId());
      evidence.setClauseId(c.clauseId());
      evidence.setStandardCode(c.standardCode());
      evidence.setClauseNo(c.clauseNo());
      evidence.setQuote(head(c.quote(), 1500));
      evidence.setItemCode("");
      evidence.setReason("适用条款召回（" + c.source() + "）");
      evidenceMapper.insert(evidence);
      evidenceViews.add(new AssessResultView.EvidenceView(c.standardCode(), c.clauseNo(),
          head(c.quote(), 600), "", evidence.getReason(), c.pageNo()));
    }

    // 推荐：标准内推荐（附录 C 关联）与机构扩展分开
    List<AssessResultView.RecommendationView> recViews = new ArrayList<>();
    Recommendation std = new Recommendation();
    std.setAssessmentId(assessment.getId());
    std.setItemCode("FOLLOWUP");
    std.setItemName("按条款要求复查");
    std.setReason("来自适用条款的检查要求");
    std.setExtended(false);
    std.setSourceClauseNo(retrieval.clauses().isEmpty() ? "" : retrieval.clauses().get(0).clauseNo());
    recommendationMapper.insert(std);
    recViews.add(new AssessResultView.RecommendationView(std.getItemCode(), std.getItemName(),
        std.getReason(), false, std.getSourceClauseNo()));

    return new AssessResultView(assessment.getId(), examId, conclusion, labelOf(conclusion),
        assessment.getConclusionSource(), evidenceViews, recViews, toolCalls,
        assessment.getPromptTokens(), assessment.getCompletionTokens(), assessment.getTotalTokens(),
        assessment.getCostMs());
  }

  private void emit(ProgressListener listener, String text) {
    if (listener != null) {
      listener.onReasoning(text);
    }
  }

  private void emitTool(ProgressListener listener, ClauseRetrievalTools.ToolCall call) {
    if (listener != null) {
      listener.onToolCall(call.tool(), call.args());
      listener.onToolResult(call.tool(), call.hits(), call.note());
    }
  }

  private Map<String, Object> toMap(ClauseRetrievalTools.ToolCall call) {
    Map<String, Object> map = new HashMap<>();
    map.put("tool", call.tool());
    map.put("args", call.args());
    map.put("hits", call.hits());
    map.put("note", call.note());
    return map;
  }

  private String labelOf(String conclusion) {
    try {
      return Conclusion.valueOf(conclusion).getLabel();
    } catch (Exception e) {
      return conclusion;
    }
  }

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }
}
