package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.entity.Clause;
import com.occuspec.entity.Standard;
import com.occuspec.mapper.ClauseMapper;
import com.occuspec.mapper.StandardMapper;
import com.occuspec.parser.ClauseSplitter;
import com.occuspec.parser.MetadataExtractor;
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
  private final ClauseSplitter splitter;
  private final Path dataDir;

  public StandardImportService(
      StandardMapper standardMapper,
      ClauseMapper clauseMapper,
      @Value("${occuspec.data-dir:../data-markdown}") String dataDir) {
    this.standardMapper = standardMapper;
    this.clauseMapper = clauseMapper;
    this.splitter = new ClauseSplitter();
    this.dataDir = Path.of(dataDir);
  }

  /** 全量导入：遍历数据目录顶层 Markdown。 */
  public ImportResult importAll() throws Exception {
    int files = 0;
    int clauses = 0;
    int skipped = 0;
    try (var stream = Files.list(dataDir)) {
      List<Path> mdFiles =
          stream.filter(p -> p.toString().endsWith(".md")).sorted().toList();
      for (Path file : mdFiles) {
        String name = file.getFileName().toString();
        if (name.startsWith("_")) {
          continue;
        }
        FileResult r = importFile(file);
        files++;
        clauses += r.imported();
        skipped += r.skipped();
      }
    }
    log.info("标准导入完成 files={} clauses={} skipped={}", files, clauses, skipped);
    return new ImportResult(files, clauses, skipped);
  }

  /** 导入单个文件，返回新增与跳过数。 */
  @Transactional
  public FileResult importFile(Path file) throws Exception {
    String markdown = Files.readString(file, StandardCharsets.UTF_8);
    String head = markdown.length() > 4000 ? markdown.substring(0, 4000) : markdown;
    String standardCode = StandardMeta.extractStandardCode(head, file.getFileName().toString());
    // 整卷哈希去重：重复文件直接跳过（指纹存 standards 表备注字段外，此处查条款的 DOC 标记需截断适配列宽）
    String docHash = sha256(markdown);
    String docMark = "DOC:" + docHash.substring(0, 32);
    Long dupDoc = clauseMapper.selectCount(
        new LambdaQueryWrapper<Clause>()
            .eq(Clause::getStandardCode, truncate(standardCode, 56))
            .eq(Clause::getClauseNo, docMark));
    if (dupDoc != null && dupDoc > 0) {
      return new FileResult(0, 1);
    }
    upsertStandard(standardCode, file.getFileName().toString());
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
      clause.setHazardCode(truncate(StandardMeta.mapHazard(chunk.clauseNo(), chunk.title()), 60));
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
    mark.setTitle(truncate(file.getFileName().toString(), 500));
    mark.setContent("__DOC_HASH__");
    mark.setContentHash(sha256(standardCode + docHash));
    clauseMapper.insert(mark);
    return new FileResult(imported, skipped);
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
