package com.occuspec;

import com.occuspec.rag.ClauseRetrievalTools;
import com.occuspec.rag.ClauseVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 向量入库冒烟测试：小批量验证嵌入调用与 PG 写入链路。
 * 需要外网嵌入服务可用；失败时仅打印，不阻断构建（网络抖动不视为回归）。
 */
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
}
