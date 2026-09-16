package com.occuspec;

import com.occuspec.rag.ClauseRetrievalTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * RAG 冒烟测试：危害路由 + 精确取条款 + 引用展开（不依赖向量，纯数据库）。
 */
@SpringBootTest
@ActiveProfiles("local")
class RagSmokeTest {
  @Autowired ClauseRetrievalTools tools;

  @Test
  void 危害路由与精确取条款() {
    var route = tools.route("noise");
    System.out.println("ROUTE noise hits=" + route.clauses().size() + " note=" + route.call().note());
    for (int i = 0; i < Math.min(5, route.clauses().size()); i++) {
      var c = route.clauses().get(i);
      System.out.println(" - " + c.standardCode() + " " + c.clauseNo() + " " + c.title());
    }
    var fetch = tools.fetch("GBZ 188-2025", "7.1");
    System.out.println("FETCH 7.1 hits=" + fetch.clauses().size());
    if (!fetch.clauses().isEmpty()) {
      var expand = tools.expand(fetch.clauses().get(0).clauseId());
      System.out.println("EXPAND hits=" + expand.clauses().size() + " note=" + expand.call().note());
    }
  }
}
