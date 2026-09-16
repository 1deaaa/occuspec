package com.occuspec;

import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 检索质量基准：对比"纯向量"与"向量 + 元数据过滤"的召回精确率。
 *
 * <p>相关性标注采用条款自身的 hazard_code（由 GBZ 188 章节结构生成，非人工临时判断）：
 * 查询某危害因素时，命中条款的 hazard_code 与查询危害一致即视为相关。
 * 输出 P@K 与相关条款占比，供工程基准文档引用。
 *
 * <p>检索需调用嵌入服务，标记 live。
 */
@Tag("live")
@SpringBootTest
@ActiveProfiles("local")
class RetrievalBenchmarkTest {
  @Autowired ClauseRetrievalTools tools;

  /** 查询集：危害因素编码 + 中文查询文本（模拟判定时的检索输入）。 */
  private record Case(String hazard, String query) {}

  private static final List<Case> CASES = List.of(
      new Case("gbz188-7-1", "噪声 职业禁忌证 目标疾病 检查内容 双耳高频平均听阈"),
      new Case("gbz188-6-1", "游离二氧化硅粉尘 职业禁忌证 目标疾病 检查内容 胸片肺功能"),
      new Case("gbz188-5-1", "铅及其无机化合物 职业禁忌证 目标疾病 检查内容 血铅尿铅"),
      new Case("gbz188-5-19", "苯 职业禁忌证 目标疾病 检查内容 血常规"),
      new Case("gbz188-6-2", "煤尘 职业禁忌证 目标疾病 检查内容 胸片"),
      new Case("gbz188-7-3", "高温 职业禁忌证 目标疾病 检查内容 血压心电图"),
      new Case("gbz188-5-58", "甲苯 职业禁忌证 目标疾病 检查内容 肝功能"),
      new Case("gbz188-9-1", "电工作业 职业禁忌证 目标疾病 检查内容 视力色觉"),
      new Case("gbz188-8-1", "布鲁氏菌 职业禁忌证 目标疾病 检查内容"),
      new Case("gbz188-6-3", "石棉粉尘 职业禁忌证 目标疾病 检查内容 胸片"));

  private static final int TOP_K = 5;

  @Test
  void 对比纯向量与元数据过滤精确率() {
    System.out.println("hazard\tmode\tP@K\thits");
    double sumPlain = 0;
    double sumFiltered = 0;
    for (Case c : CASES) {
      double plain = precision(c, false);
      double filtered = precision(c, true);
      sumPlain += plain;
      sumFiltered += filtered;
    }
    double avgPlain = sumPlain / CASES.size();
    double avgFiltered = sumFiltered / CASES.size();
    System.out.printf("AVG\tplain=%.3f\tfiltered=%.3f\tlift=%.1f%%%n",
        avgPlain, avgFiltered, (avgFiltered - avgPlain) / Math.max(1e-9, avgPlain) * 100);
  }

  /** 计算单个查询的 P@K：返回结果中 hazard_code 与查询一致的比例。 */
  private double precision(Case c, boolean useFilter) {
    var filters = useFilter
        ? new ClauseVectorStore.Filters(c.hazard(), null, null, null, null)
        : ClauseVectorStore.Filters.NONE;
    var result = tools.retrieve(c.query(), TOP_K, filters);
    int relevant = 0;
    for (var clause : result.clauses()) {
      if (c.hazard().equals(clause.hazardCode())) {
        relevant++;
      }
    }
    int total = result.clauses().size();
    double p = total == 0 ? 0 : (double) relevant / total;
    System.out.printf("%s\t%s\t%.3f\t%d/%d%n",
        c.hazard(), useFilter ? "filtered" : "plain", p, relevant, total);
    return p;
  }
}
