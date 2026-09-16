package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.entity.Clause;
import com.occuspec.entity.Hazard;
import com.occuspec.entity.Standard;
import com.occuspec.mapper.ClauseMapper;
import com.occuspec.mapper.HazardMapper;
import com.occuspec.mapper.StandardMapper;
import com.occuspec.parser.ClauseSplitter;
import com.occuspec.parser.HazardCatalogParser;
import com.occuspec.parser.HazardResolver;
import com.occuspec.parser.MetadataExtractor;
import com.occuspec.parser.PhaseExtractor;
import com.occuspec.parser.StandardMeta;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标准解析入库服务：读取 Markdown，按条款切分，提取元数据后落库。
 * 两份重复的 GBZ 188 按正文哈希去重。
 */
@Service
public class StandardImportService {
  private static final Logger log = LoggerFactory.getLogger(StandardImportService.class);
  private final StandardMapper standardMapper;
  private final ClauseMapper clauseMapper;
  private final HazardMapper hazardMapper;
  private final ClauseSplitter splitter;
  private final PhaseExtractor phaseExtractor;
  private final HazardCatalogParser hazardCatalogParser;
  private final Path dataDir;
  /** 危害因素解析器：首次导入 GBZ 188 后按目录构建，供后续标准按名称匹配。 */
  private volatile HazardResolver hazardResolver;

  public StandardImportService(
      StandardMapper standardMapper,
      ClauseMapper clauseMapper,
      HazardMapper hazardMapper,
      @Value("${occuspec.data-dir:../data-markdown}") String dataDir) {
    this.standardMapper = standardMapper;
    this.clauseMapper = clauseMapper;
    this.hazardMapper = hazardMapper;
    this.splitter = new ClauseSplitter();
    this.phaseExtractor = new PhaseExtractor();
    this.hazardCatalogParser = new HazardCatalogParser();
    this.dataDir = Path.of(dataDir);
  }

  /** 全量导入：遍历数据目录顶层 Markdown。 */
  public ImportResult importAll() throws Exception {
    // 先导入 GBZ 188，用其目录初始化危害因素表与解析器，其余标准按名称匹配
    List<Path> mdFiles = listMarkdown();
    Path gbz188 = mdFiles.stream()
        .filter(p -> p.getFileName().toString().startsWith("GBZ188"))
        .findFirst()
        .orElse(null);
    int files = 0;
    int clauses = 0;
    int skipped = 0;
    if (gbz188 != null) {
      FileResult r = importFile(gbz188);
      files++;
      clauses += r.imported();
      skipped += r.skipped();
    }
    for (Path file : mdFiles) {
      if (file.equals(gbz188)) {
        continue;
      }
      FileResult r = importFile(file);
      files++;
      clauses += r.imported();
      skipped += r.skipped();
    }
    log.info("标准导入完成 files={} clauses={} skipped={}", files, clauses, skipped);
    return new ImportResult(files, clauses, skipped);
  }

  /** 列出待导入的 Markdown 文件（跳过下划线前缀）。 */
  private List<Path> listMarkdown() throws Exception {
    try (var stream = Files.list(dataDir)) {
      return stream
          .filter(p -> p.toString().endsWith(".md"))
          .filter(p -> !p.getFileName().toString().startsWith("_"))
          .sorted()
          .toList();
    }
  }

  /** 导入单个文件，返回新增与跳过数。 */
  @Transactional
  public FileResult importFile(Path file) throws Exception {
    String markdown = Files.readString(file, StandardCharsets.UTF_8);
    String head = markdown.length() > 4000 ? markdown.substring(0, 4000) : markdown;
    String standardCode = StandardMeta.extractStandardCode(head, file.getFileName().toString());
    String fileName = file.getFileName().toString();
    // GBZ 188：初始化危害因素目录（97 项）与解析器
    if (HazardResolver.isGbz188(standardCode)) {
      initializeHazards(markdown, standardCode);
    }
    // 整卷哈希去重：重复文件直接跳过
    String docHash = sha256(markdown);
    String docMark = "DOC:" + docHash.substring(0, 32);
    Long dupDoc = clauseMapper.selectCount(
        new LambdaQueryWrapper<Clause>()
            .eq(Clause::getStandardCode, truncate(standardCode, 56))
            .eq(Clause::getClauseNo, docMark));
    if (dupDoc != null && dupDoc > 0) {
      return new FileResult(0, 1);
    }
    upsertStandard(standardCode, fileName);
    String standardName = fileName.replaceAll("\\.md$", "");
    List<ClauseSplitter.Chunk> chunks = splitter.split(standardCode, markdown);
    int imported = 0;
    int skipped = 0;
    for (ClauseSplitter.Chunk chunk : chunks) {
      if (chunk.content() == null || chunk.content().isBlank()) {
        skipped++;
        continue;
      }
      String hash = sha256(chunk.content());
      Long exists = clauseMapper.selectCount(
          new LambdaQueryWrapper<Clause>().eq(Clause::getContentHash, hash));
      if (exists != null && exists > 0) {
        skipped++;
        continue;
      }
      Clause clause = new Clause();
      clause.setStandardCode(truncate(standardCode, 60));
      clause.setClauseNo(truncate(chunk.clauseNo(), 60));
      clause.setTitle(truncate(chunk.title(), 500));
      clause.setContent(chunk.content());
      clause.setPageNo(chunk.pageNo() == 0 ? null : chunk.pageNo());
      clause.setAppendixType(truncate(chunk.appendixType(), 12));
      clause.setHazardCode(truncate(resolveHazard(standardCode, standardName, chunk), 60));
      clause.setPhase(truncate(
          phaseExtractor.extract(chunk.clauseNo(), chunk.title(), chunk.content()), 12));
      clause.setCheckClass(truncate(MetadataExtractor.extractCheckClass(chunk.content()), 12));
      clause.setTargetText(truncate(MetadataExtractor.extractTarget(chunk.content()), 1000));
      clause.setPeriodText(truncate(MetadataExtractor.extractPeriod(chunk.content()), 250));
      clause.setForceType(truncate(MetadataExtractor.forceType(appendixLetter(chunk), chunk.content()), 12));
      clause.setRelations(toJson(chunk.relations()));
      clause.setContentHash(hash);
      try {
        clauseMapper.insert(clause);
        imported++;
      } catch (Exception ex) {
        // 唯一键冲突视为重复跳过
        skipped++;
        log.debug("条款重复跳过 std={} no={}", standardCode, chunk.clauseNo());
      }
    }
    // 记录整卷指纹，防重复导入
    Clause mark = new Clause();
    mark.setStandardCode(truncate(standardCode, 56));
    mark.setClauseNo(docMark);
    mark.setTitle(truncate(fileName, 500));
    mark.setContent("__DOC_HASH__");
    mark.setContentHash(sha256(standardCode + docHash));
    clauseMapper.insert(mark);
    return new FileResult(imported, skipped);
  }

  /** 初始化危害因素表（97 项）与解析器；已存在则确保解析器可用。 */
  private void initializeHazards(String markdown, String standardCode) {
    if (hazardResolver != null) {
      return;
    }
    List<HazardCatalogParser.HazardItem> items = hazardCatalogParser.parse(markdown);
    for (HazardCatalogParser.HazardItem item : items) {
      String code = HazardCatalogParser.toCode(item.sectionNo());
      Hazard existing = hazardMapper.selectById(code);
      if (existing != null) {
        continue;
      }
      Hazard hazard = new Hazard();
      hazard.setCode(code);
      hazard.setName(truncate(item.name(), 120));
      hazard.setCategory(truncate(item.category(), 16));
      hazard.setExposureLimit("");
      hazard.setSectionNo(item.sectionNo());
      hazard.setSourceStandard(truncate(standardCode, 60));
      hazard.setAliases(toJson(item.aliases()));
      try {
        hazardMapper.insert(hazard);
      } catch (Exception ex) {
        log.debug("危害因素已存在 code={}", code);
      }
    }
    hazardResolver = new HazardResolver(items);
    log.info("危害因素目录初始化完成 count={}", items.size());
  }

  /** 解析危害因素：优先用已构建的解析器，未初始化时按标准号回退。 */
  private String resolveHazard(String standardCode, String standardName, ClauseSplitter.Chunk chunk) {
    HazardResolver resolver = hazardResolver;
    if (resolver != null) {
      return resolver.resolve(standardCode, standardName, chunk.clauseNo());
    }
    // 非 GBZ 188 标准先于 188 导入时的兜底：仅按名称匹配
    return new HazardResolver(List.of()).resolve(standardCode, standardName, chunk.clauseNo());
  }

  private void upsertStandard(String code, String fileName) {
    String stdCode = truncate(code, 60);
    Standard existing = standardMapper.selectById(stdCode);
    if (existing != null) {
      return;
    }
    Standard standard = new Standard();
    standard.setCode(stdCode);
    standard.setName(truncate(fileName.replaceAll("\\.md$", ""), 250));
    standard.setVersion("");
    standard.setStatus("ACTIVE");
    standardMapper.insert(standard);
  }

  private String appendixLetter(ClauseSplitter.Chunk chunk) {
    if (chunk.clauseNo() != null && chunk.clauseNo().matches("[A-G](\\..*)?")) {
      return chunk.clauseNo().substring(0, 1);
    }
    return "";
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

  private String sha256(String text) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  public record ImportResult(int files, int clauses, int skipped) {}
  public record FileResult(int imported, int skipped) {}
}
