package com.occuspec;

import com.occuspec.service.StandardImportService;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 标准导入冒烟测试：导入 3 个代表性文件，验证切分与落库。
 * 需要本地 MySQL/PG/Redis 运行，不调用真实模型。
 */
@SpringBootTest
@ActiveProfiles("local")
@MapperScan("com.occuspec.mapper")
class StandardImportSmokeTest {
  @Autowired StandardImportService importService;

  @Test
  void 导入代表性文件() throws Exception {
    Path dataDir = Path.of("..", "data-markdown");
    var r1 = importService.importFile(dataDir.resolve("金属烟热诊断标准.md"));
    System.out.println("烟热 imported=" + r1.imported() + " skipped=" + r1.skipped());
    var r2 = importService.importFile(dataDir.resolve("GBZ 188—2025职业健康监护技术规范.md"));
    System.out.println("GBZ188 imported=" + r2.imported() + " skipped=" + r2.skipped());
    var r3 = importService.importFile(dataDir.resolve("GBZ159-2004-工作场所空气中有害物质监测的采样规范.md"));
    System.out.println("GBZ159 imported=" + r3.imported() + " skipped=" + r3.skipped());
  }

  @Test
  void 全量导入() throws Exception {
    Path dataDir = Path.of("..", "data-markdown");
    List<String> names = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dataDir, "*.md")) {
      for (Path p : stream) {
        names.add(p.getFileName().toString());
      }
    }
    names.sort(String::compareTo);
    int files = 0;
    int clauses = 0;
    for (String name : names) {
      var r = importService.importFile(dataDir.resolve(name));
      files++;
      clauses += r.imported();
      if (files % 50 == 0) {
        System.out.println("进度 files=" + files + " clauses=" + clauses);
      }
    }
    System.out.println("全量完成 files=" + files + " clauses=" + clauses);
  }
}
