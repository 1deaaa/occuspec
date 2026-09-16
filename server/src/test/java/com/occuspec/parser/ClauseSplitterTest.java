package com.occuspec.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 切分器单测：不联网，纯内存断言。 */
class ClauseSplitterTest {
  private final ClauseSplitter splitter = new ClauseSplitter();

  @Test
  void 按数字条款切分() {
    String md = "# 4.1 总则说明\n内容A\n# 5.1 铅及其化合物\n内容B\n# 5.1.1 上岗前检查\n内容C\n";
    List<ClauseSplitter.Chunk> chunks = splitter.split("GBZ 188-2025", md);
    assertTrue(chunks.size() >= 3);
    assertEquals("4.1", chunks.get(0).clauseNo());
    assertEquals("5.1", chunks.get(1).clauseNo());
  }

  @Test
  void 附录条款独立成块() {
    String md = "# 附 录 A （资料性）\n# A.1 说明文字\n# A.2 更多说明\n";
    List<ClauseSplitter.Chunk> chunks = splitter.split("GBZ 188-2025", md);
    assertTrue(chunks.stream().anyMatch(c -> "A.1".equals(c.clauseNo())));
    assertTrue(chunks.stream().anyMatch(c -> "资料性".equals(c.appendixType())));
  }

  @Test
  void 页码只做属性不切分() {
    String md = "<!-- page 16 -->\n# 5.1.1 上岗前\n内容\n<!-- page 17 -->\n更多内容\n";
    List<ClauseSplitter.Chunk> chunks = splitter.split("GBZ 188-2025", md);
    assertEquals(1, chunks.size());
    assertEquals(16, chunks.get(0).pageNo());
  }

  @Test
  void 破损标题合并() {
    String md = "# 7.1 噪声\n- 7.1.2\n# 在岗期间职业健康检查\n内容\n";
    List<ClauseSplitter.Chunk> chunks = splitter.split("GBZ 188-2025", md);
    assertTrue(chunks.stream().anyMatch(c -> "7.1.2".equals(c.clauseNo())));
  }

  @Test
  void 超长块二次切分() {
    StringBuilder sb = new StringBuilder("# 5.1 铅及其化合物\n");
    for (int i = 0; i < 200; i++) {
      sb.append("补充行内容填充长度测试，用来撑大块体积。\n");
    }
    sb.append("# 5.1.1 上岗前检查\n内容A\n# 5.1.2 在岗期间检查\n内容B\n");
    List<ClauseSplitter.Chunk> chunks = splitter.split("GBZ 188-2025", sb.toString());
    assertTrue(chunks.size() >= 2);
  }
}
