package com.occuspec;

import com.occuspec.common.HotCache;
import com.occuspec.rag.ClauseRetrievalTools;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 缓存收益基准：命中率与 P95 响应时间对比（冷回源 vs 热命中）。
 *
 * <p>结果写入 docs/benchmark.md，本测试只输出可复现的实测数字。
 */
@SpringBootTest
@ActiveProfiles("local")
class CacheBenchmarkTest {
  @Autowired ClauseRetrievalTools tools;
  @Autowired HotCache hotCache;

  /** 模拟热点查询：同一批键被反复访问（判定场景下条款查询高度集中）。 */
  @Test
  void 缓存命中率与延迟对比() {
    // 构造一批热点键：真实业务中判定反复查同一批条款
    List<String> hotKeys = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      hotKeys.add("bench:hot:" + i);
    }
    // 清空一级缓存与统计，保证"冷启动"可复现（一级缓存为进程级单例）
    for (String key : hotKeys) {
      hotCache.evict(key);
    }
    hotCache.clearLocal();
    hotCache.resetStats();

    // 阶段一：冷启动，首次访问全部回源
    long coldStart = System.nanoTime();
    List<Long> coldLatency = new ArrayList<>();
    for (String key : hotKeys) {
      long t0 = System.nanoTime();
      hotCache.get(key, Duration.ofMinutes(5), () -> {
        try {
          Thread.sleep(5);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return "value-" + key;
      });
      coldLatency.add((System.nanoTime() - t0) / 1_000_000);
    }
    long coldTotal = (System.nanoTime() - coldStart) / 1_000_000;

    // 阶段二：热命中，模拟 200 次随机访问
    List<Long> hotLatency = new ArrayList<>();
    long hotStart = System.nanoTime();
    for (int i = 0; i < 200; i++) {
      String key = hotKeys.get(ThreadLocalRandom.current().nextInt(hotKeys.size()));
      long t0 = System.nanoTime();
      hotCache.get(key, Duration.ofMinutes(5), () -> "unexpected-reload");
      hotLatency.add((System.nanoTime() - t0) / 1_000_000);
    }
    long hotTotal = (System.nanoTime() - hotStart) / 1_000_000;

    var stats = hotCache.stats();
    System.out.println("=== 缓存基准 ===");
    System.out.printf("冷启动 20 次回源总耗时=%dms 平均=%.2fms  P95=%dms%n",
        coldTotal, avg(coldLatency), p95(coldLatency));
    System.out.printf("热命中 200 次总耗时=%dms 平均=%.3fms  P95=%dms%n",
        hotTotal, avg(hotLatency), p95(hotLatency));
    System.out.printf("命中率=%.4f (hits=%d misses=%d total=%d)%n",
        stats.hitRate(), stats.hits(), stats.misses(), stats.total());
  }

  /** 条款精确查询：同一批条款反复查询，观察真实查询路径的缓存收益。 */
  @Test
  void 条款查询缓存收益() {
    String[][] keys = {
        {"GBZ 188-2025", "7.1.1.1"},
        {"GBZ 188-2025", "7.1.2.1"},
        {"GBZ 188-2025", "6.1.2.1"},
        {"GBZ 188-2025", "4.8.2.2"},
        {"GBZ 188-2025", "5.1.1.1"},
    };
    // 清空一级缓存，保证冷启动计量准确
    hotCache.clearLocal();
    hotCache.resetStats();
    // 冷启动
    long cold0 = System.nanoTime();
    for (String[] key : keys) {
      tools.fetch(key[0], key[1]);
    }
    long cold = (System.nanoTime() - cold0) / 1_000_000;
    // 热命中：每条约款再查 40 次，共 200 次
    List<Long> hotLatency = new ArrayList<>();
    long hot0 = System.nanoTime();
    for (int i = 0; i < 200; i++) {
      String[] key = keys[i % keys.length];
      long t0 = System.nanoTime();
      tools.fetch(key[0], key[1]);
      hotLatency.add((System.nanoTime() - t0) / 1_000_000);
    }
    long hot = (System.nanoTime() - hot0) / 1_000_000;
    var stats = hotCache.stats();
    System.out.println("=== 条款查询缓存基准 ===");
    System.out.printf("冷启动 %d 条总耗时=%dms 平均=%.2fms%n", keys.length, cold, (double) cold / keys.length);
    System.out.printf("热命中 200 次总耗时=%dms 平均=%.3fms  P95=%dms%n",
        hot, avg(hotLatency), p95(hotLatency));
    System.out.printf("命中率=%.4f (hits=%d misses=%d)%n",
        stats.hitRate(), stats.hits(), stats.misses());
  }

  private double avg(List<Long> values) {
    return values.stream().mapToLong(Long::longValue).average().orElse(0);
  }

  /** P95：升序后取 95 分位。 */
  private long p95(List<Long> values) {
    if (values.isEmpty()) {
      return 0;
    }
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(Long::compareTo);
    int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
    return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
  }
}
