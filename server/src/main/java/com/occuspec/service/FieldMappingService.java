package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.entity.ItemFieldMapping;
import com.occuspec.mapper.ItemFieldMappingMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 字段映射服务：把外部报告字段名归一化为规则表达式使用的规范 fact 编码。
 *
 * <p>报告来源多样，同一检查项可能写作"血铅/血铅值/blood_lead"等；
 * 规则表达式依赖稳定的 fact 编码。此处维护别名到规范编码的映射，
 * 归一化后规则才能稳定命中，避免因字段名差异导致漏判。
 *
 * <p>映射表整体加载为内存快照（表小、变更少），带 TTL 自动刷新；
 * 查不到时回退原样字段名，保证未收录字段也可参与判定，不因映射缺失而丢弃数据。
 */
@Service
public class FieldMappingService {
  private static final Logger log = LoggerFactory.getLogger(FieldMappingService.class);
  private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);

  private final ItemFieldMappingMapper mappingMapper;
  /** 当前快照：别名 → 映射。 */
  private volatile Snapshot snapshot;

  public FieldMappingService(ItemFieldMappingMapper mappingMapper) {
    this.mappingMapper = mappingMapper;
  }

  /** 内存快照：别名映射表 + 加载时间。 */
  private record Snapshot(Map<String, ItemFieldMapping> byAlias, Instant loadedAt) {}

  /**
   * 归一化字段名：命中别名映射返回规范编码，否则返回去空白后的原名。
   *
   * @param rawField 外部字段名，如"双耳高频平均听阈"
   */
  public String canonicalize(String rawField) {
    if (rawField == null || rawField.isBlank()) {
      return "";
    }
    ItemFieldMapping mapping = current().byAlias().get(normalize(rawField));
    return mapping == null ? rawField.trim() : mapping.getCanonicalCode();
  }

  /** 取规范中文名，用于展示；未收录时回退原字段名。 */
  public String canonicalName(String rawField) {
    if (rawField == null || rawField.isBlank()) {
      return "";
    }
    ItemFieldMapping mapping = current().byAlias().get(normalize(rawField));
    if (mapping == null || mapping.getCanonicalName() == null || mapping.getCanonicalName().isBlank()) {
      return rawField.trim();
    }
    return mapping.getCanonicalName();
  }

  /** 批量归一化：保留原键，新增规范编码键，两套键都可被规则引用。 */
  public Map<String, Object> canonicalizeFacts(Map<String, Object> facts) {
    Map<String, Object> result = new HashMap<>();
    for (Map.Entry<String, Object> entry : facts.entrySet()) {
      result.put(entry.getKey(), entry.getValue());
      String canonical = canonicalize(entry.getKey());
      if (!canonical.isBlank()) {
        result.putIfAbsent(canonical, entry.getValue());
      }
    }
    return result;
  }

  /** 新增或更新映射（数据录入侧调用），随后立即刷新快照。 */
  public void upsert(String alias, String canonicalCode, String canonicalName, String unit, String source) {
    if (alias == null || alias.isBlank() || canonicalCode == null || canonicalCode.isBlank()) {
      return;
    }
    String normalized = normalize(alias);
    ItemFieldMapping existing = mappingMapper.selectOne(
        new LambdaQueryWrapper<ItemFieldMapping>()
            .eq(ItemFieldMapping::getAlias, normalized)
            .last("LIMIT 1"));
    if (existing == null) {
      ItemFieldMapping mapping = new ItemFieldMapping();
      mapping.setAlias(normalized);
      mapping.setCanonicalCode(canonicalCode);
      mapping.setCanonicalName(canonicalName == null ? "" : canonicalName);
      mapping.setUnit(unit == null ? "" : unit);
      mapping.setSource(source == null ? "MANUAL" : source);
      mappingMapper.insert(mapping);
    } else if (!canonicalCode.equals(existing.getCanonicalCode())) {
      existing.setCanonicalCode(canonicalCode);
      existing.setCanonicalName(canonicalName == null ? "" : canonicalName);
      mappingMapper.updateById(existing);
    }
    snapshot = null;
  }

  /** 失效快照，下次访问重新加载。 */
  public void invalidate() {
    snapshot = null;
  }

  /** 取当前快照，过期则重载。 */
  private Snapshot current() {
    Snapshot local = snapshot;
    if (local != null && Duration.between(local.loadedAt(), Instant.now()).compareTo(SNAPSHOT_TTL) < 0) {
      return local;
    }
    synchronized (this) {
      if (snapshot != null
          && Duration.between(snapshot.loadedAt(), Instant.now()).compareTo(SNAPSHOT_TTL) < 0) {
        return snapshot;
      }
      snapshot = load();
      return snapshot;
    }
  }

  private Snapshot load() {
    Map<String, ItemFieldMapping> byAlias = new HashMap<>();
    try {
      List<ItemFieldMapping> mappings = mappingMapper.selectList(null);
      for (ItemFieldMapping mapping : mappings) {
        byAlias.put(normalize(mapping.getAlias()), mapping);
      }
      log.debug("字段映射快照加载完成 count={}", byAlias.size());
    } catch (Exception ex) {
      log.warn("字段映射加载失败，降级为空映射 err={}", ex.getMessage());
    }
    return new Snapshot(byAlias, Instant.now());
  }

  /** 归一化别名键：去空白、转小写，保证"双耳 高频"与"双耳高频"一致。 */
  private String normalize(String alias) {
    return alias == null ? "" : alias.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
  }
}
