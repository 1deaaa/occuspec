package com.occuspec.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 条款切分器：按条款编号文本切分，不依赖标题层级。
 * 切分键：数字条款（章/条/款）+ 附录条款（A.1 类）+ 表格/图独立成块。
 * 页码标记与眉题只做属性，不做切分边界。
 */
public class ClauseSplitter {
  // 数字条款：标题行（# 或 - 开头）+ 数字编号（如 5.1.2、4.8.2.2）。
  // 两个变体：
  // 1) "# 4.1 目的"：# 前缀 + 编号 + 空格 + 标题文字；
  // 2) "- 4.1.1 早期发现..."：短横 + 编号 + 空格 + 标题文字（GBZ188 第 4/5 章大量使用）。
  // 要点："- GB 20827 xxx" 编号组捕获到 "20827" 后无 \b+标题结构？实际 "20827 职业..." 会误命中，
  // 由 isClauseNo 排除：纯 5 位数字（标准序号）不是条款。
  // 章标题（# 4 / ## 1）保留；"- 13 加压试验" 类正文引用（短横+纯整数）不切分。
  private static final Pattern NUMERIC_CLAUSE =
      Pattern.compile("^((?:#{1,4}|-)\\s+)(\\d+(?:\\.\\d+){0,3})\\s+(.*\\S.*)$");
  // 附录条款：A.1 / B.2.3 类（# 或 - 前缀），编号后需跟标题文字
  private static final Pattern APPENDIX_CLAUSE =
      Pattern.compile("^((?:#{1,4}|-)\\s+)([A-G]\\.\\d+(?:\\.\\d+)*)\\b(.*\\S.*)$");
  // 表格/图标题独立成块
  private static final Pattern TABLE_TITLE = Pattern.compile("^\\s*#{0,2}\\s*(表[A-Z]?\\.?\\d*|图[A-Z]?\\.?\\d*)\\b(.*)$");
  // 页码标记只做属性
  private static final Pattern PAGE_MARK = Pattern.compile("<!--\\s*page\\s+(\\d+)\\s*-->");
  // 眉题行（标准号重复出现）跳过
  private static final Pattern BROW_TITLE = Pattern.compile("^\\s*GBZ\\s*188[－—-]2025\\s*$");
  // 附录标题行：附录 X（规范性/资料性），兼容 "# <mark>附 录 B</mark>" 无性质字样变体
  // （B 无字样时按目次"附录B（规范性）"回填，见 APPENDIX_DEFAULT_TYPE）
  private static final Pattern APPENDIX_HEAD =
      Pattern.compile("附\\s*录\\s*([A-G])\\s*[（(]?\\s*(规范性|资料性)?\\s*[）)]?");
  // 附录默认性质（GBZ 188 目次）：B 规范性、D 规范性，其余资料性；用于标题无字样时的回填
  private static final java.util.Map<String, String> APPENDIX_DEFAULT_TYPE =
      java.util.Map.of("A", "资料性", "B", "规范性", "C", "资料性", "D", "规范性",
          "E", "资料性", "F", "资料性", "G", "资料性");
  // 二次切分阈值
  public static final int SPLIT_CHARS = 2500;
  public static final int SPLIT_LINES = 120;

  /** 切分结果块。 */
  public record Chunk(
      String clauseNo, String title, String content, int pageNo, String appendixType, List<String> relations) {}

  /**
   * 切分单个标准全文。
   *
   * @param standardCode 标准号
   * @param markdown 全文
   * @return 条款块列表
   */
  public List<Chunk> split(String standardCode, String markdown) {
    List<RawBlock> raw = firstPass(markdown);
    List<Chunk> result = new ArrayList<>();
    for (RawBlock block : raw) {
      if (block.contentChars() > SPLIT_CHARS || block.lineCount() > SPLIT_LINES) {
        result.addAll(secondSplit(block));
      } else {
        result.add(block.toChunk());
      }
    }
    return result;
  }

  /** 第一遍：按条款编号切块，同时归因页码与附录类型。 */
  List<RawBlock> firstPass(String markdown) {
    List<RawBlock> blocks = new ArrayList<>();
    // 注意：源文件为 CRLF 换行，切分后每行行尾残留 \r，正则 ^$ 锚点全部失效，需先去除。
    String[] lines = markdown.split("\n", -1);
    RawBlock current = null;
    int pageNo = 0;
    String appendixType = "";
    String appendixLetter = "";
    // 破损标题合并：上一行是孤立编号（如 "- 7.1.2"），下一行是标题正文时合并
    String pendingNo = null;
    for (String rawLine : lines) {
      // 源文件为 CRLF 换行，行尾残留 \r 会导致 ^$ 锚点全部失效，必须先去除
      String line = rawLine == null ? "" : rawLine.replace("\r", "");
      Matcher pageMatcher = PAGE_MARK.matcher(line);
      if (pageMatcher.find()) {
        pageNo = Integer.parseInt(pageMatcher.group(1));
        continue;
      }
      if (BROW_TITLE.matcher(line).matches()) {
        continue;
      }
      Matcher appendixHead = APPENDIX_HEAD.matcher(line);
      if (appendixHead.find()) {
        appendixLetter = appendixHead.group(1);
        String found = appendixHead.group(2);
        if (found == null || found.isBlank()) {
          found = APPENDIX_DEFAULT_TYPE.getOrDefault(appendixLetter, "");
        }
        appendixType = found;
        continue;
      }
      String trimmed = line.trim();
      // 孤立编号行：仅 "- 7.1.2" 独占一行（无标题文字）时暂存，等待与下一行 # 标题合并。
      // "- 5.1.1 上岗前职业健康检查" 自带标题，直接走正常条款分支，不进入此逻辑。
      if (trimmed.matches("-\\s+\\d+(\\.\\d+){0,3}") || trimmed.matches("-\\s+[A-G]\\.\\d+(\\.\\d+)*")) {
        pendingNo = trimmed.replaceFirst("^-\\s+", "").trim();
        continue;
      }
      Matcher numeric = NUMERIC_CLAUSE.matcher(line);
      Matcher appendix = APPENDIX_CLAUSE.matcher(line);
      Matcher table = TABLE_TITLE.matcher(line);
      Matcher matched = null;
      String clauseNo = null;
      String title = "";
      boolean dashPrefix = line.startsWith("-");
      String numericNo = numeric.matches() ? numeric.group(2) : null;
      if (numeric.matches() && isClauseNo(numericNo, dashPrefix)) {
        matched = numeric;
        clauseNo = numericNo;
        title = numeric.group(3).trim();
      } else if (appendix.matches()) {
        matched = appendix;
        clauseNo = appendix.group(2);
        title = appendix.group(3).trim();
      } else if (table.matches()) {
        // 表格标题独立成块，编号用表号
        if (current != null) {
          blocks.add(current);
        }
        current = new RawBlock(table.group(1), table.group(2).trim(), pageNo, appendixType, appendixLetter);
        current.append(line);
        pendingNo = null;
        continue;
      }
      if (pendingNo != null && matched == null) {
        // 破损标题：孤立编号 + 下一行 # 标题合并为新块；如下一行不是标题则把孤立行吐回正文。
        if (line.startsWith("#")) {
          if (current != null) {
            blocks.add(current);
          }
          current = new RawBlock(pendingNo, line.replaceAll("^#{1,4}\\s+", "").trim(), pageNo, appendixType, appendixLetter);
          current.append(line);
          pendingNo = null;
          continue;
        } else {
          // 不是标题：孤立编号行本身是正文（如 "- 7.1.2" 格式错乱），吐回当前块
          if (current == null) {
            current = new RawBlock("0", "前言", pageNo, appendixType, appendixLetter);
          }
          current.append(pendingNo);
          pendingNo = null;
        }
      }
      if (matched != null) {
        if (pendingNo != null) {
          pendingNo = null;
        }
        if (current != null) {
          blocks.add(current);
        }
        current = new RawBlock(clauseNo, title, pageNo, appendixType, appendixLetter);
        current.append(line);
      } else {
        if (current == null) {
          current = new RawBlock("0", "前言", pageNo, appendixType, appendixLetter);
        }
        current.append(line);
      }
    }
    if (current != null) {
      blocks.add(current);
    }
    return blocks;
  }

  /**
   * 是否为真实条款编号：排除纯页码、年份等噪音。
   * 短横开头的纯整数（如 "- 13 加压试验"）是正文引用，不切分；
   * # 开头的纯整数（如 "# 4 总则"）是章标题，保留。
   */
  boolean isClauseNo(String no) {
    return isClauseNo(no, false);
  }

  boolean isClauseNo(String no, boolean dashPrefix) {
    if (no == null || no.isBlank()) {
      return false;
    }
    // 纯年份（如 2025）不是条款
    if (no.matches("19\\d{2}|20\\d{2}")) {
      return false;
    }
    // 纯 4~5 位数字是引用标准序号（如 GB 20827），不是条款
    if (no.matches("\\d{4,5}")) {
      return false;
    }
    // 短横+纯整数是正文引用（如 "- 13 加压试验"），不是条款标题
    if (dashPrefix && !no.contains(".")) {
      return false;
    }
    return true;
  }

  /** 第二遍：超长块按 X.Y.1–.4（上岗前/在岗/离岗/应急）再切。 */
  List<Chunk> secondSplit(RawBlock block) {
    // 子节编号：在块内以 "X.Y.N " 开头的行
    Pattern subSection = Pattern.compile("^\\s*(#{0,4}\\s+)?(-\\s+)?(\\d+\\.\\d+\\.\\d+)\\b(.*)$");
    List<RawBlock> parts = new ArrayList<>();
    RawBlock current = null;
    for (String line : block.lines) {
      Matcher m = subSection.matcher(line);
      if (m.matches()) {
        if (current != null) {
          parts.add(current);
        }
        current = new RawBlock(m.group(3), m.group(4).trim(), block.pageNo, block.appendixType, block.appendixLetter);
      } else if (current == null) {
        current = new RawBlock(block.clauseNo, block.title, block.pageNo, block.appendixType, block.appendixLetter);
      }
      current.append(line);
    }
    if (current != null) {
      parts.add(current);
    }
    if (parts.size() <= 1) {
      return List.of(block.toChunk());
    }
    List<Chunk> result = new ArrayList<>();
    for (RawBlock part : parts) {
      result.add(part.toChunk());
    }
    return result;
  }

  /** 原始块。 */
  static class RawBlock {
    final String clauseNo;
    final String title;
    final int pageNo;
    final String appendixType;
    final String appendixLetter;
    final List<String> lines = new ArrayList<>();

    RawBlock(String clauseNo, String title, int pageNo, String appendixType, String appendixLetter) {
      this.clauseNo = clauseNo;
      this.title = title == null ? "" : title;
      this.pageNo = pageNo;
      this.appendixType = appendixType == null ? "" : appendixType;
      this.appendixLetter = appendixLetter == null ? "" : appendixLetter;
    }

    void append(String line) {
      lines.add(line);
    }

    int contentChars() {
      int n = 0;
      for (String l : lines) {
        n += l.length();
      }
      return n;
    }

    int lineCount() {
      return lines.size();
    }

    Chunk toChunk() {
      String content = String.join("\n", lines).trim();
      return new Chunk(clauseNo, title, content, pageNo, appendixType, MetadataExtractor.extractRelations(content));
    }
  }
}
