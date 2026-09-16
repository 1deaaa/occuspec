package com.occuspec.service;

import com.occuspec.entity.Hazard;
import com.occuspec.mapper.HazardMapper;
import com.occuspec.parser.HazardCatalogParser;
import com.occuspec.parser.HazardResolver;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 危害因素注册表：持有危害因素目录与解析器，负责初始化与查表。
 * 从导入服务中独立出来，使解析器状态不依赖导入流程的生命周期，也便于单独测试。
 */
@Component
public class HazardRegistry {
  private static final Logger log = LoggerFactory.getLogger(HazardRegistry.class);

  private final HazardMapper hazardMapper;
  private final HazardCatalogParser catalogParser = new HazardCatalogParser();
  private final AtomicReference<HazardResolver> resolverRef = new AtomicReference<>();

  public HazardRegistry(HazardMapper hazardMapper) {
    this.hazardMapper = hazardMapper;
  }

  /** 是否已初始化目录。 */
  public boolean initialized() {
    return resolverRef.get() != null;
  }

  /**
   * 依据 GBZ 188 全文初始化危害因素表与解析器（幂等）。
   *
   * @param markdown GBZ 188 全文
   * @param sourceStandard 来源标准号
   * @return 危害因素条目数
   */
  public int initialize(String markdown, String sourceStandard) {
    List<HazardCatalogParser.HazardItem> items = catalogParser.parse(markdown);
    for (HazardCatalogParser.HazardItem item : items) {
      String code = HazardCatalogParser.toCode(item.sectionNo());
      try {
        if (hazardMapper.selectById(code) != null) {
          continue;
        }
        Hazard hazard = new Hazard();
        hazard.setCode(code);
        hazard.setName(truncate(item.name(), 120));
        hazard.setCategory(truncate(item.category(), 16));
        hazard.setExposureLimit("");
        hazard.setSectionNo(item.sectionNo());
        hazard.setSourceStandard(truncate(sourceStandard, 60));
        hazard.setAliases(toJson(item.aliases()));
        hazardMapper.insert(hazard);
      } catch (Exception ex) {
        log.debug("危害因素写入跳过 code={} err={}", code, ex.getMessage());
      }
    }
    resolverRef.set(new HazardResolver(items));
    log.info("危害因素目录初始化完成 count={}", items.size());
    return items.size();
  }

  /** 解析条款归属的危害因素；未初始化时按标准名匹配（不锁定测量类标准）。 */
  public String resolve(String standardCode, String standardName, String clauseNo) {
    HazardResolver resolver = resolverRef.get();
    if (resolver == null) {
      resolver = new HazardResolver(List.of());
    }
    return resolver.resolve(standardCode, standardName, clauseNo);
  }

  private String truncate(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }

  private String toJson(List<String> list) {
    if (list == null || list.isEmpty()) {
      return "[]";
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < list.size(); i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("\"").append(list.get(i).replace("\"", "")).append("\"");
    }
    return sb.append("]").toString();
  }
}
