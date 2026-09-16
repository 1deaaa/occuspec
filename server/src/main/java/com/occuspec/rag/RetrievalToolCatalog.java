package com.occuspec.rag;

import com.occuspec.llm.LlmToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 检索工具目录：集中装配检索类工具的声明（含动态枚举）。
 *
 * <p>把工具 schema 从各个编排服务里抽出来原因有二：
 * <ol>
 *   <li>消除重复：对话与判定两条链路需要同一组检索工具，此前各自拼装、容易漂移；</li>
 *   <li>枚举不写死：过滤维度的合法取值来自向量库的实际分布（{@code describeMetadata}），
 *       新增危害因素或标准后，模型看到的可选值与代码零改动保持同步。</li>
 * </ol>
 *
 * <p>高基数字段（危害因素约 97 项）不放进 {@code enum}，而是把取值摘要写入 description，
 * 完整取值由 {@code describe_metadata} 工具按需返回，避免 schema 体积过大。
 */
@Component
public class RetrievalToolCatalog {
  /** 危害因素编码在 description 中列出的最大条数。 */
  private static final int HAZARD_HINT_LIMIT = 40;

  private final ClauseRetrievalTools retrievalTools;

  public RetrievalToolCatalog(ClauseRetrievalTools retrievalTools) {
    this.retrievalTools = retrievalTools;
  }

  /** 检索类工具：与具体编排（对话/判定）无关，两个链路共用。 */
  public List<LlmToolSpec> retrievalTools() {
    List<LlmToolSpec> tools = new ArrayList<>();
    tools.add(describeMetadataTool());
    tools.add(clauseRetrieveTool());
    tools.add(clauseFetchTool());
    tools.add(clauseExpandTool());
    tools.add(hazardRouteTool());
    return tools;
  }

  private LlmToolSpec describeMetadataTool() {
    return new LlmToolSpec("describe_metadata",
        "列出可用的检索过滤维度及其取值分布（含数量）。当你不确定危害因素编码、"
            + "检查阶段等取值时，先调用本工具。",
        Map.of("type", "object", "properties", Map.of(), "required", List.of()));
  }

  /** 语义检索工具：phase/checkClass/appendixType/standard 用实际取值做 enum。 */
  private LlmToolSpec clauseRetrieveTool() {
    Map<String, Map<String, Integer>> dims = safeDimensions();
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("query", Map.of("type", "string", "description", "检索文本，用中文描述要查的内容"));
    properties.put("topK", Map.of("type", "integer", "description", "返回条数，默认 6"));
    properties.put("hazard", Map.of("type", "string",
        "description", "危害因素编码，可选。常见取值：" + hazardHint(dims)
            + "（完整取值见 describe_metadata）"));
    properties.put("standard", enumProperty("标准号，可选",
        keys(dims.get("standard_code")), null));
    properties.put("phase", enumProperty("检查阶段，可选",
        keys(dims.get("phase")), List.of("上岗前", "在岗期间", "离岗时", "应急")));
    properties.put("checkClass", enumProperty("检查类别，可选",
        keys(dims.get("check_class")), List.of("必检", "补充", "选检")));
    properties.put("appendixType", enumProperty("附录类型，可选",
        keys(dims.get("appendix_type")), List.of("规范性", "资料性")));
    return new LlmToolSpec("clause_retrieve",
        "按语义检索职业卫生标准条款。可按危害因素、检查阶段、检查类别、标准号、附录类型过滤；"
            + "不确定时不要传过滤条件（不传即全库检索）。",
        Map.of("type", "object", "properties", properties, "required", List.of("query")));
  }

  private LlmToolSpec clauseFetchTool() {
    return new LlmToolSpec("clause_fetch",
        "按标准号与条款编号精确获取条款原文。已知具体条款（如 GBZ 188-2025 的 7.1.1.1）时使用。",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "standard", Map.of("type", "string", "description", "标准号"),
                "clause", Map.of("type", "string", "description", "条款编号，如 7.1.1.1")),
            "required", List.of("standard", "clause")));
  }

  private LlmToolSpec clauseExpandTool() {
    return new LlmToolSpec("clause_expand",
        "沿条款引用链展开：取回该条款引用的其他条款（如\"同 7.1.1.1\"、\"按 GBZ 49\"、附录引用）。"
            + "当条款写\"同 X.Y\"或\"参见附录\"时用它取回被引内容。",
        Map.of(
            "type", "object",
            "properties", Map.of("clauseId", Map.of("type", "integer", "description", "条款主键")),
            "required", List.of("clauseId")));
  }

  private LlmToolSpec hazardRouteTool() {
    return new LlmToolSpec("hazard_route",
        "按危害因素列出相关标准的节级条款清单，用于快速了解该危害因素涉及哪些标准与章节。",
        Map.of(
            "type", "object",
            "properties", Map.of("hazard", Map.of("type", "string", "description", "危害因素编码")),
            "required", List.of("hazard")));
  }

  /**
   * 构造带 enum 的属性；取值为空时回退到内置默认值（如向量库尚未入库时）。
   * 内置默认值仅作兜底，正常情况一律以实际取值为准。
   */
  private Map<String, Object> enumProperty(String description, List<String> actual, List<String> fallback) {
    Map<String, Object> property = new LinkedHashMap<>();
    property.put("type", "string");
    List<String> values = actual != null && !actual.isEmpty() ? actual : fallback;
    if (values != null && !values.isEmpty()) {
      property.put("description", description + " 可选值：" + String.join("/", values));
      property.put("enum", values);
    } else {
      property.put("description", description);
    }
    return property;
  }

  /** 维度分布：向量库不可用时返回空表，不影响工具声明可用性。 */
  private Map<String, Map<String, Integer>> safeDimensions() {
    try {
      var discovery = retrievalTools.describeMetadata();
      return discovery.dimensions() == null ? Map.of() : discovery.dimensions();
    } catch (Exception ex) {
      return Map.of();
    }
  }

  private List<String> keys(Map<String, Integer> distribution) {
    return distribution == null ? List.of() : new ArrayList<>(distribution.keySet());
  }

  /** 危害因素取值的简洁提示：按名称数量截断。 */
  private String hazardHint(Map<String, Map<String, Integer>> dims) {
    List<String> codes = keys(dims.get("hazard_code"));
    if (codes.isEmpty()) {
      return "（见 describe_metadata）";
    }
    int limit = Math.min(HAZARD_HINT_LIMIT, codes.size());
    String joined = String.join("、", codes.subList(0, limit));
    return codes.size() > limit ? joined + " 等 " + codes.size() + " 项" : joined;
  }
}
