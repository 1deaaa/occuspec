package com.occuspec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 串行 vs 并发吞吐基准：用可控延迟模拟 IO 密集任务（模型调用），
 * 量化"分批并发 + 信号量限流"相对串行的加速比与限流有效性。
 *
 * <p>不依赖外部服务，结果稳定可复现；真实上游耗时远高于模拟值，加速比结论一致。
 */
class ConcurrencyBenchmarkTest {
  /** 模拟单次 IO 延迟（毫秒）：真实模型调用为数十秒，此处按比例缩小以便快速跑完。 */
  private static final long IO_DELAY_MS = 20;
  /** 任务总数：贴近单次向量入库或批量判定的规模。 */
  private static final int TASK_COUNT = 40;
  /** 并发度：与 ClauseRetrievalTools 的 Semaphore(4) 一致。 */
  private static final int CONCURRENCY = 4;

  @Test
  void 串行与并发耗时对比() {
    long serialMs = runSerial();
    Result concurrent = runConcurrent();
    System.out.println("=== 并发基准（任务 " + TASK_COUNT + " 个，单个 IO 延迟 "
        + IO_DELAY_MS + "ms，并发度 " + CONCURRENCY + "）===");
    System.out.printf("串行：总耗时=%dms，吞吐=%.1f 任务/秒%n",
        serialMs, TASK_COUNT * 1000.0 / Math.max(1, serialMs));
    System.out.printf("并发：总耗时=%dms，吞吐=%.1f 任务/秒，峰值并发=%d%n",
        concurrent.totalMs(), TASK_COUNT * 1000.0 / Math.max(1, concurrent.totalMs()),
        concurrent.peakConcurrency());
    System.out.printf("加速比=%.2fx（理论上限 %d）%n",
        (double) serialMs / Math.max(1, concurrent.totalMs()), CONCURRENCY);
  }

  /** 串行执行：逐个等待。 */
  private long runSerial() {
    long start = System.nanoTime();
    for (int i = 0; i < TASK_COUNT; i++) {
      sleep();
    }
    return (System.nanoTime() - start) / 1_000_000;
  }

  private record Result(long totalMs, int peakConcurrency) {}

  /** 并发执行：CompletableFuture + Semaphore 限流，统计峰值并发。 */
  private Result runConcurrent() {
    ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
    Semaphore semaphore = new Semaphore(CONCURRENCY);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    long start = System.nanoTime();
    for (int i = 0; i < TASK_COUNT; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          semaphore.acquire();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        try {
          int now = active.incrementAndGet();
          peak.accumulateAndGet(now, Math::max);
          sleep();
          active.decrementAndGet();
        } finally {
          semaphore.release();
        }
      }, pool));
    }
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    } finally {
      pool.shutdown();
    }
    return new Result((System.nanoTime() - start) / 1_000_000, peak.get());
  }

  /** 限流有效性：并发度上限被 Semaphore 严格约束。 */
  @Test
  void 信号量限流不超上限() {
    Semaphore semaphore = new Semaphore(CONCURRENCY);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    ExecutorService pool = Executors.newFixedThreadPool(32);
    for (int i = 0; i < 100; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          semaphore.acquire();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        try {
          peak.accumulateAndGet(active.incrementAndGet(), Math::max);
          sleep();
          active.decrementAndGet();
        } finally {
          semaphore.release();
        }
      }, pool));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    pool.shutdown();
    System.out.println("=== 限流验证 ===");
    System.out.printf("32 线程提交 100 个任务，Semaphore(%d) 下峰值并发=%d%n",
        CONCURRENCY, peak.get());
  }

  private void sleep() {
    try {
      Thread.sleep(IO_DELAY_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
