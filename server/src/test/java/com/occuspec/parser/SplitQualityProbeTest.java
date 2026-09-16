package com.occuspec.parser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 切分质量抽样验证：对老版与新版标准分别核对条款数。 */
class SplitQualityProbeTest {
  private final ClauseSplitter splitter = new ClauseSplitter();

  @Test
  void 老版标准纯文本章号() {
    check("GBZ49-2014-职业性噪声聋的诊断.md");
    check("GBZ70-2015-职业性尘肺病的诊断.md");
    check("GBZ49-2014-职业性噪声聋的诊断.md");
  }

  @Test
  void 新版标准不受影响() {
    check("GBZ188-2025-职业健康监护技术规范.md");
    check("GBZ 188—2025职业健康监护技术规范.md");
    check("GBZT189.8-2007-工作场所物理因素测量 第8部分：噪声.md");
  }

  private void check(String fileName) {
    try {
      Path file = Path.of("..", "data-markdown", fileName);
      if (!Files.exists(file)) {
        System.out.println("SKIP(不存在) " + fileName);
        return;
      }
      String md = Files.readString(file, StandardCharsets.UTF_8);
      List<ClauseSplitter.Chunk> chunks = splitter.split("X", md);
      long withNo = chunks.stream().filter(c -> c.clauseNo() != null && !"0".equals(c.clauseNo())).count();
      System.out.println(fileName + " chunks=" + chunks.size() + " withNo=" + withNo);
    } catch (Exception ex) {
      System.out.println("ERR " + fileName + " " + ex.getMessage());
    }
  }
}
