package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.dto.ExamCreateRequest;
import com.occuspec.entity.ReportUpload;
import com.occuspec.entity.ReportUploadItem;
import com.occuspec.mapper.ReportUploadItemMapper;
import com.occuspec.mapper.ReportUploadMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 报告上传服务：编排"上传 → 模型抽取 → 人工核对 → 确认入库"四步。
 *
 * <p>三段分离的原因：模型抽取存在误差，抽取结果只能作为草稿；
 * 人工核对表是责任边界所在，只有被人工确认（confirmed=true）的项才可写入体检记录。
 * 该流程保证判定输入的数据来源可追溯到"人已核对"，满足医疗辅助工具的责任要求。
 */
@Service
public class ReportUploadService {
  private static final Logger log = LoggerFactory.getLogger(ReportUploadService.class);

  private final ReportUploadMapper uploadMapper;
  private final ReportUploadItemMapper itemMapper;
  private final ReportExtractionService extractionService;
  private final FieldMappingService fieldMappingService;
  private final ExamService examService;

  public ReportUploadService(
      ReportUploadMapper uploadMapper, ReportUploadItemMapper itemMapper,
      ReportExtractionService extractionService, FieldMappingService fieldMappingService,
      ExamService examService) {
    this.uploadMapper = uploadMapper;
    this.itemMapper = itemMapper;
    this.extractionService = extractionService;
    this.fieldMappingService = fieldMappingService;
    this.examService = examService;
  }

  /**
   * 上传并抽取：保存抽取草稿，状态置 EXTRACTED，等待人工核对。
   *
   * @param bytes 图片字节
   * @param fileName 原始文件名
   * @param mimeType 图片类型
   * @param personId 关联体检对象（可空）
   * @param hazardCode 危害因素编码
   * @param personHint 受检者信息提示（姓名/工号等，可空）
   * @param creatorId 操作者
   */
  @Transactional
  public UploadView uploadAndExtract(
      byte[] bytes, String fileName, String mimeType, Long personId, String hazardCode,
      String personHint, String creatorId) {
    if (bytes == null || bytes.length == 0) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请上传报告图片");
    }
    ReportUpload upload = new ReportUpload();
    upload.setFileName(fileName == null ? "" : head(fileName, 250));
    upload.setMimeType(mimeType == null ? "" : head(mimeType, 60));
    upload.setFileSize((long) bytes.length);
    upload.setPersonId(personId);
    upload.setHazardCode(hazardCode == null ? "" : head(hazardCode, 60));
    upload.setStatus("EXTRACTED");
    upload.setCreatorId(creatorId == null ? "" : creatorId);
    uploadMapper.insert(upload);

    String hazardName = hazardCode == null || hazardCode.isBlank()
        ? "" : hazardCode;
    var result = extractionService.extract(List.of(
        new ReportExtractionService.ExtractionRequest(bytes, mimeType, hazardName, personHint)));
    upload.setRawText(head(result.rawJson(), 60000));
    upload.setExtractNote(head(result.note(), 500));
    upload.setCostMs(result.costMs());
    if (result.degraded()) {
      upload.setStatus("FAILED");
    }
    uploadMapper.updateById(upload);

    List<ReportUploadItem> saved = new ArrayList<>();
    for (var item : result.items()) {
      ReportUploadItem entity = new ReportUploadItem();
      entity.setUploadId(upload.getId());
      entity.setItemName(head(item.itemName(), 120));
      entity.setItemCode(head(item.itemCode(), 60));
      entity.setValueNum(item.valueNum());
      entity.setValueText(head(item.valueText(), 1000));
      entity.setUnit(head(item.unit(), 30));
      entity.setConfidence(item.confidence() == null ? BigDecimal.ZERO : item.confidence());
      entity.setConfirmed(false);
      itemMapper.insert(entity);
      saved.add(entity);
    }
    log.info("报告上传抽取 uploadId={} items={} degraded={}", upload.getId(), saved.size(), result.degraded());
    return toView(upload, saved);
  }

  /** 读取上传记录（含核对项）。 */
  public UploadView get(long uploadId) {
    ReportUpload upload = uploadMapper.selectById(uploadId);
    if (upload == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "上传记录不存在");
    }
    return toView(upload, listItems(uploadId));
  }

  /** 上传记录列表。 */
  public List<Map<String, Object>> list(int limit) {
    var wrapper = new LambdaQueryWrapper<ReportUpload>()
        .orderByDesc(ReportUpload::getId).last("LIMIT " + Math.max(1, Math.min(limit, 100)));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ReportUpload upload : uploadMapper.selectList(wrapper)) {
      Map<String, Object> row = new HashMap<>();
      row.put("uploadId", upload.getId());
      row.put("fileName", upload.getFileName());
      row.put("status", upload.getStatus());
      row.put("hazardCode", upload.getHazardCode());
      row.put("personId", upload.getPersonId());
      row.put("extractNote", upload.getExtractNote());
      row.put("createdAt", String.valueOf(upload.getCreatedAt()));
      rows.add(row);
    }
    return rows;
  }

  /** 人工核对：提交修正后的检查项与采纳状态，整体覆盖该上传的核对项。 */
  @Transactional
  public UploadView confirm(long uploadId, List<ConfirmItem> items) {
    ReportUpload upload = uploadMapper.selectById(uploadId);
    if (upload == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "上传记录不存在");
    }
    itemMapper.delete(new LambdaQueryWrapper<ReportUploadItem>()
        .eq(ReportUploadItem::getUploadId, uploadId));
    List<ReportUploadItem> saved = new ArrayList<>();
    for (ConfirmItem item : items == null ? List.<ConfirmItem>of() : items) {
      if (item == null || item.itemName() == null || item.itemName().isBlank()) {
        continue;
      }
      ReportUploadItem entity = new ReportUploadItem();
      entity.setUploadId(uploadId);
      entity.setItemName(head(item.itemName(), 120));
      // 人工可能修正了名称，重新做一次归一化，保证规则能命中
      entity.setItemCode(head(
          item.itemCode() == null || item.itemCode().isBlank()
              ? fieldMappingService.canonicalize(item.itemName()) : item.itemCode(), 60));
      entity.setValueNum(item.valueNum());
      entity.setValueText(head(item.valueText(), 1000));
      entity.setUnit(head(item.unit(), 30));
      entity.setConfidence(item.confidence() == null ? BigDecimal.ONE : item.confidence());
      entity.setConfirmed(item.confirmed() == null || item.confirmed());
      itemMapper.insert(entity);
      saved.add(entity);
    }
    upload.setStatus("CONFIRMED");
    uploadMapper.updateById(upload);
    return toView(upload, saved);
  }

  /**
   * 确认入库：把已采纳（confirmed=true）的核对项写入体检记录。
   * 这是数据进入判定链路前的最后一道人工闸门。
   */
  @Transactional
  public Map<String, Object> importToExam(long uploadId, Long personId, String examDate, String operatorId) {
    ReportUpload upload = uploadMapper.selectById(uploadId);
    if (upload == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "上传记录不存在");
    }
    Long targetPerson = personId != null ? personId : upload.getPersonId();
    if (targetPerson == null) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择体检对象后再入库");
    }
    List<ReportUploadItem> items = listItems(uploadId).stream()
        .filter(i -> Boolean.TRUE.equals(i.getConfirmed()))
        .toList();
    if (items.isEmpty()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "没有已采纳的检查项，无法入库");
    }
    List<ExamCreateRequest.Item> examItems = new ArrayList<>();
    for (ReportUploadItem item : items) {
      examItems.add(new ExamCreateRequest.Item(
          item.getItemCode(), item.getItemName(), item.getValueNum(), item.getValueText(), item.getUnit()));
    }
    var request = new ExamCreateRequest(
        targetPerson, upload.getHazardCode(),
        examDate == null || examDate.isBlank()
            ? java.time.LocalDate.now() : java.time.LocalDate.parse(examDate),
        examItems);
    Map<String, Object> created = examService.create(request, operatorId);
    Object examId = created.get("examId");
    upload.setExamId(examId == null ? null : Long.valueOf(String.valueOf(examId)));
    upload.setStatus("IMPORTED");
    uploadMapper.updateById(upload);
    log.info("报告入库 uploadId={} examId={} items={}", uploadId, examId, examItems.size());
    Map<String, Object> data = new HashMap<>(created);
    data.put("uploadId", uploadId);
    data.put("importedItems", examItems.size());
    return data;
  }

  /** 核对项提交结构。 */
  public record ConfirmItem(
      String itemName, String itemCode, BigDecimal valueNum, String valueText,
      String unit, BigDecimal confidence, Boolean confirmed) {}

  /** 上传记录视图。 */
  public record UploadView(
      long uploadId, String fileName, String status, String extractNote, Long examId,
      Long personId, String hazardCode, List<Map<String, Object>> items) {}

  private UploadView toView(ReportUpload upload, List<ReportUploadItem> items) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (ReportUploadItem item : items) {
      Map<String, Object> row = new HashMap<>();
      row.put("id", item.getId());
      row.put("itemName", item.getItemName());
      row.put("itemCode", item.getItemCode());
      row.put("valueNum", item.getValueNum());
      row.put("valueText", item.getValueText());
      row.put("unit", item.getUnit());
      row.put("confidence", item.getConfidence());
      row.put("confirmed", item.getConfirmed());
      rows.add(row);
    }
    return new UploadView(upload.getId(), upload.getFileName(), upload.getStatus(),
        upload.getExtractNote(), upload.getExamId(), upload.getPersonId(),
        upload.getHazardCode(), rows);
  }

  private List<ReportUploadItem> listItems(long uploadId) {
    return itemMapper.selectList(new LambdaQueryWrapper<ReportUploadItem>()
        .eq(ReportUploadItem::getUploadId, uploadId).orderByAsc(ReportUploadItem::getId));
  }

  private String head(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) : text;
  }
}
