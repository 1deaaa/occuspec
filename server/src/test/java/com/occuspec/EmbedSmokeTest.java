package com.occuspec;

import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 向量入库冒烟测试：小批量验证嵌入调用与 PG 写入链路。
 * 需外网嵌入服务，标记 live，批量回归默认排除。
 */
@Tag("live")
@SpringBootTest
@ActiveProfiles("local")
class EmbedSmokeTest {
  @Autowired ClauseRetrievalTools tools;
  @Autowired ClauseVectorStore vectorStore;

  @Test
  void 小批量入库() {
    long before = vectorStore.count();
    System.out.println("VECTORS_BEFORE=" + before);
    var result = tools.embedClauses(4, 8);
    System.out.println("EMBED done=" + result.done() + " failed=" + result.failed());
    long after = vectorStore.count();
    System.out.println("VECTORS_AFTER=" + after);
  }

  /**
   * 全量入库：约 35 分钟、消耗大量上游额度，仅供首次部署或向量重建时手动执行。
   * 常规测试与回归一律跳过，避免误触发重复入库。
   */
  @Disabled("全量重建向量的重型运维操作，需手动执行；常规流程用 /admin/rag/embed 接口")
  @Test
  void 全量入库() {
    long before = vectorStore.count();
    System.out.println("FULL_BEFORE=" + before);
    var result = tools.embedClauses(16, 6000);
    System.out.println("FULL done=" + result.done() + " failed=" + result.failed());
    long after = vectorStore.count();
    System.out.println("FULL_AFTER=" + after);
  }
}
