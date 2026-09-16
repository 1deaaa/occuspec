package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.entity.Clause;
import com.occuspec.entity.Standard;
import com.occuspec.mapper.ClauseMapper;
import com.occuspec.mapper.StandardMapper;
import com.occuspec.parser.ClauseSplitter;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标准解析入库服务：读取 Markdown，按条款切分，提取元数据后落库。
 *
 * <p>事务边界说明：单文件导入的方法由 {@link #importAll()} 经注入的自身代理调用，
 * 而非 this 直接调用，避免 Spring AOP 自调用导致 {@code @Transactional} 失效。
 */
@Service
public class StandardImportService {
  private static final Logger log = LoggerFactory.getLogger(StandardImportService.class);

  private final StandardMapper standardMapper;
  private final ClauseMapper clauseMapper;
  private final HazardRegistry hazardRegistry;
  private final ClauseSplitter splitter;
  private final PhaseExtractor phaseExtractor;
  private final Path dataDir;
  /** 自身代理（延迟注入，避免构造期循环依赖）：用于让单文件导入的事务注解生效。 */
  private final StandardImportService self;

  public StandardImportService(
      StandardMapper standardMapper,
      ClauseMapper clauseMapper,
      HazardRegistry hazardRegistry,
      @Value("${occuspec.data-dir:../data-markdown}") String dataDir,
      @org.springframework.context.annotation.Lazy StandardImportService self) {
    this.standardMapper = standardMapper;
    this.clauseMapper = clauseMapper;
    this.hazardRegistry = hazardRegistry;
    this.splitter = new ClauseSplitter();
    this.phaseExtractor = new PhaseExtractor();
    this.dataDir = Path.of(dataDir);
    this.self = self;
  }

  /** 全量导入：先导入 GBZ 188 初始化危害因素目录，再导入其余标准。 */
  public ImportResult importAll() throws Exception {
    List<Path> mdFiles = listMarkdown();
    Path gbz188 = mdFiles.stream()
        .filter(p -> p.getFileName().toString().startsWith("GBZ188"))
        .findFirst()
        .orElse(null);
    int files = 0;
    int clauses = 0;
    int skipped = 0;
    if (gbz188 != null) {
      FileResult result = self.importFile(gbz188);
      files++;
      clauses += result.imported();
      skipped += result.skipped();
    }
    for (Path file : mdFiles) {
      if (file.equals(gbz188)) {
        continue;
      }
      FileResult result = self.importFile(file);
      files++;
      clauses += result.imported();
      skipped += result.skipped();
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

  /**
   * 导入单个文件：整卷事务，任一条款异常不回滚已成功的其他条款（条级别容错）。
   * 由外部（{@link #importAll()} 经代理，或控制器直接调用）进入，保证事务注解生效。
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public FileResult importFile(Path file) throws Exception {
    String markdown = Files.readString(file, StandardCharsets.UTF_8);
    String head = markdown.length() > 4000 ? markdown.substring(0, 4000) : markdown;
    String standardCode = StandardMeta.extractStandardCode(head, file.getFileName().toString());
    String fileName = file.getFileName().toString();
    if (HazardResolver.isGbz188(standardCode) && !hazardRegistry.initialized()) {
      hazardRegistry.initialize(markdown, standardCode);
    }
    String docHash = sha256(markdown);
    String docMark = "DOC:" + docHash.substring(0, 32);
    Long dupDoc = clauseMapper.selectCount(new LambdaQueryWrapper<Clause>()
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
      clause.setHazardCode(truncate(
          hazardRegistry.resolve(standardCode, standardName, chunk.clauseNo()), 60));
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
        skipped++;
        log.debug("条款重复跳过 std={} no={}", standardCode, chunk.clauseNo());
      }
    }
    Clause mark = new Clause();
    mark.setStandardCode(truncate(standardCode, 56));
    mark.setClauseNo(docMark);
    mark.setTitle(truncate(fileName, 500));
    mark.setContent("__DOC_HASH__");
    mark.setContentHash(sha256(standardCode + docHash));
    clauseMapper.insert(mark);
    return new FileResult(imported, skipped);
  }

  private void upsertStandard(String code, String fileName) {
    String stdCode = truncate(code, 60);
    if (standardMapper.selectById(stdCode) != null) {
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
