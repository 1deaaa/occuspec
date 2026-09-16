package com.occuspec.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 热点缓存行为验证：命中、穿透占位、击穿单飞。
 */
@SpringBootTest
@ActiveProfiles("local")
class HotCacheTest {
  @Autowired HotCache hotCache;

  @Test
  void 命中后不再回源() {
    String key = "test:hit:" + System.nanoTime();
    AtomicInteger loads = new AtomicInteger();
    String first = hotCache.get(key, Duration.ofMinutes(5), () -> {
      loads.incrementAndGet();
      return "v1";
    });
    String second = hotCache.get(key, Duration.ofMinutes(5), () -> {
      loads.incrementAndGet();
      return "v2";
    });
    assertEquals("v1", first);
    assertEquals("v1", second, "二次读取应走缓存");
    assertEquals(1, loads.get(), "回源应只发生一次");
    hotCache.evict(key);
  }

  @Test
  void 空结果以占位缓存防穿透() {
    String key = "test:null:" + System.nanoTime();
    AtomicInteger loads = new AtomicInteger();
    assertNull(hotCache.get(key, Duration.ofMinutes(5), () -> {
      loads.incrementAndGet();
      return null;
    }));
    assertNull(hotCache.get(key, Duration.ofMinutes(5), () -> {
      loads.incrementAndGet();
      return "late";
    }), "空值占位期内应返回 null，不回源");
    assertEquals(1, loads.get(), "空结果回源应只发生一次，避免穿透");
    hotCache.evict(key);
  }

  @Test
  void 并发回源单飞只查一次() throws Exception {
    String key = "test:single-flight:" + System.nanoTime();
    AtomicInteger loads = new AtomicInteger();
    int threads = 16;
    List<CompletableFuture<String>> futures = new CopyOnWriteArrayList<>();
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    for (int i = 0; i < threads; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return hotCache.get(key, Duration.ofMinutes(5), () -> {
          loads.incrementAndGet();
          try {
            Thread.sleep(80);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return "shared";
        });
      }));
    }
    start.countDown();
    for (CompletableFuture<String> f : futures) {
      assertEquals("shared", f.get());
    }
    assertEquals(1, loads.get(), "并发回源应被单飞收敛为一次");
    hotCache.evict(key);
  }

  @Test
  void 回源异常向调用方传播() {
    String key = "test:error:" + System.nanoTime();
    assertThrows(IllegalStateException.class, () -> hotCache.get(key, Duration.ofMinutes(1), () -> {
      throw new IllegalStateException("boom");
    }));
    assertTrue(true);
    hotCache.evict(key);
  }
}
