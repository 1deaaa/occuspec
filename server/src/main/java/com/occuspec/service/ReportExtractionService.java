package com.occuspec.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.occuspec.llm.LlmGateway;
import com.occuspec.llm.LlmImage;
import com.occuspec.llm.LlmResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 报告抽取服务：把上传的报告图片经多模态模型抽取为结构化检查项。
 *
 * <p>设计要点：
 * <ul>
 *   <li>只做抽取，不做判定；抽取结果一律进入人工核对，不直接落库为体检数据；</li>
 *   <li>模型被要求输出严格 JSON 并自评置信度，便于前端突出低置信项供重点核对；</li>
 *   <li>归一化：抽取到的中文项名经字段映射层转规范编码，使后续规则可命中；</li>
 *   <li>失败降级为空列表并给出说明，不抛穿。</li>
 * </ul>
 */
@Service
public class ReportExtractionService {
  private static final Logger log = LoggerFactory.getLogger(ReportExtractionService.class);
  /** 单张图片大小上限：8MB（Base64 后约 10.7MB，控制请求体积与上游耗时）。 */
  private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

  private final LlmGateway llmGateway;
  private final FieldMappingService fieldMappingService;
  private final ObjectMapper objectMapper;

  public ReportExtractionService(
      LlmGateway llmGateway, FieldMappingService fieldMappingService, ObjectMapper objectMapper) {
    this.llmGateway = llmGateway;
    this.fieldMappingService = fieldMappingService;
    this.objectMapper = objectMapper;
  }

  /** 抽取结果：结构化项 + 原始 JSON + 说明。 */
  public record ExtractionResult(
      List<ExtractedItem> items, String rawJson, String note, boolean degraded, long costMs) {}

  /** 抽取到的单个检查项。 */
  public record ExtractedItem(
      String itemName, String itemCode, BigDecimal valueNum, String valueText,
      String unit, BigDecimal confidence) {}

  /** 抽取请求：图片字节 + 类型 + 上下文提示。 */
  public record ExtractionRequest(byte[] bytes, String mimeType, String hazardName, String personHint) {}

  /**
   * 抽取报告图片为结构化检查项。
   *
   * @param request 抽取请求（支持一次传多张：多页报告）
   */
  public ExtractionResult extract(List<ExtractionRequest> request) {
    long start = System.currentTimeMillis();
    if (request == null || request.isEmpty()) {
      return new ExtractionResult(List.of(), "{}", "未提供图片", true, 0);
    }
    List<LlmImage> images = new ArrayList<>();
    for (ExtractionRequest part : request) {
      if (part == null || part.bytes() == null || part.bytes().length == 0) {
        continue;
      }
      if (part.bytes().length > MAX_IMAGE_BYTES) {
        return new ExtractionResult(List.of(), "{}",
            "图片过大（超过 8MB），请压缩后重试", true, System.currentTimeMillis() - start);
      }
      images.add(new LlmImage(part.mimeType(),
          Base64.getEncoder().encodeToString(part.bytes())));
    }
    if (images.isEmpty()) {
      return new ExtractionResult(List.of(), "{}", "未提供有效图片", true, 0);
    }

    String hint = buildHint(request.get(0));
    LlmResult result = llmGateway.completeWithImages(SYSTEM_PROMPT, hint, images);
    long cost = System.currentTimeMillis() - start;
    if (result.degraded() || result.text() == null || result.text().isBlank()) {
      log.warn("报告抽取降级 costMs={}", cost);
      return new ExtractionResult(List.of(), "{}", "模型抽取失败，请重试或手工录入", true, cost);
    }
    List<ExtractedItem> items = parseItems(result.text());
    String note = items.isEmpty() ? "未从图片中识别到检查项，请核对图片清晰度" : "识别 " + items.size() + " 项";
    log.info("报告抽取完成 items={} costMs={} tokens={}", items.size(), cost, result.usage().totalTokens());
    return new ExtractionResult(items, result.text(), note, false, cost);
  }

  /** 系统提示词：约束输出格式与边界。 */
  private static final String SYSTEM_PROMPT = """
      你是职业健康体检报告的结构化抽取助手。请从上传的报告图片中提取全部检查项结果。

      输出要求（严格遵守）：
      1. 只输出一个 JSON 对象，不要任何解释文字、不要 Markdown 代码块围栏。
      2. JSON 结构为：{"items":[{"itemName":"","valueNum":null,"valueText":"","unit":"","confidence":0.0}]}
      3. itemName 用报告中的原始名称；数值结果填 valueNum（数字，无则 null）；
         文字结果（如"阴性""未见异常"）填 valueText；单位填 unit。
      4. confidence 为 0-1 的小数，表示你对这一项识别准确的把握；
         图片模糊或数值不确定时给低分（<0.6），不要臆造数值。
      5. 只抽取报告中明确写出的结果，不要推断或补充报告中没有的项目。
      6. 如果图片不是体检报告或无法识别，返回 {"items":[]}。
      """;

  private String buildHint(ExtractionRequest first) {
    StringBuilder sb = new StringBuilder();
    sb.append("请抽取这份职业健康体检报告中的检查项。");
    if (first.hazardName() != null && !first.hazardName().isBlank()) {
      sb.append("本次涉及的危害因素为：").append(first.hazardName()).append("。");
    }
    if (first.personHint() != null && !first.personHint().isBlank()) {
      sb.append("受检者信息：").append(first.personHint()).append("。");
    }
    return sb.toString();
  }

  /** 解析模型输出的 JSON：容错处理代码块围栏与字段缺失。 */
  private List<ExtractedItem> parseItems(String text) {
    List<ExtractedItem> items = new ArrayList<>();
    String json = stripFence(text);
    try {
      JsonNode root = objectMapper.readTree(json);
      JsonNode array = root.path("items");
      if (!array.isArray()) {
        return items;
      }
      for (JsonNode node : array) {
        String name = node.path("itemName").asText("");
        if (name.isBlank()) {
          continue;
        }
        BigDecimal valueNum = null;
        JsonNode numNode = node.path("valueNum");
        if (numNode.isNumber()) {
          valueNum = numNode.decimalValue();
        } else if (numNode.isTextual() && !numNode.asText().isBlank()) {
          try {
            valueNum = new BigDecimal(numNode.asText().trim());
          } catch (NumberFormatException ignored) {
            valueNum = null;
          }
        }
        BigDecimal confidence = BigDecimal.ZERO;
        JsonNode confNode = node.path("confidence");
        if (confNode.isNumber()) {
          confidence = confNode.decimalValue();
        }
        items.add(new ExtractedItem(
            name.trim(),
            fieldMappingService.canonicalize(name.trim()),
            valueNum,
            blankToEmpty(node.path("valueText").asText("")),
            blankToEmpty(node.path("unit").asText("")),
            confidence));
      }
    } catch (Exception ex) {
      log.warn("报告抽取 JSON 解析失败 err={} head={}", ex.getMessage(), head(json, 200));
    }
    return items;
  }

  /** 去掉可能的 Markdown 代码块围栏。 */
  private String stripFence(String text) {
    String trimmed = text == null ? "" : text.trim();
    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline > 0) {
        trimmed = trimmed.substring(firstNewline + 1);
      }
      int lastFence = trimmed.lastIndexOf("```");
      if (lastFence >= 0) {
        trimmed = trimmed.substring(0, lastFence);
      }
    }
    return trimmed.trim();
  }

  private String blankToEmpty(String text) {
    return text == null ? "" : text.trim();
  }

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }
}
