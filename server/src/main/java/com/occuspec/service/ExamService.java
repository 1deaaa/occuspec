package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.common.AuditLog;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.common.Idempotency;
import com.occuspec.dto.ExamCreateRequest;
import com.occuspec.entity.Exam;
import com.occuspec.entity.ExamItem;
import com.occuspec.entity.Person;
import com.occuspec.mapper.ExamItemMapper;
import com.occuspec.mapper.ExamMapper;
import com.occuspec.mapper.PersonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 体检录入服务：人员校验 + 记录与明细同一事务落库。
 */
@Service
public class ExamService {
  private final PersonMapper personMapper;
  private final ExamMapper examMapper;
  private final ExamItemMapper examItemMapper;
  private final AuditService auditService;

  public ExamService(
      PersonMapper personMapper, ExamMapper examMapper, ExamItemMapper examItemMapper, AuditService auditService) {
    this.personMapper = personMapper;
    this.examMapper = examMapper;
    this.examItemMapper = examItemMapper;
    this.auditService = auditService;
  }

  /** 创建体检记录（含明细），同一事务。 */
  @Transactional
  @Idempotency
  @AuditLog(action = "创建体检记录")
  public Map<String, Object> create(ExamCreateRequest request, String operatorId) {
    Person person = personMapper.selectById(request.personId());
    if (person == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "体检对象不存在");
    }
    Exam exam = new Exam();
    exam.setPersonId(request.personId());
    exam.setHazardCode(request.hazardCode());
    exam.setExamDate(request.examDate());
    exam.setOperatorId(operatorId == null ? "" : operatorId);
    exam.setStatus("SUBMITTED");
    examMapper.insert(exam);
    List<Map<String, Object>> saved = new ArrayList<>();
    for (ExamCreateRequest.Item item : request.items()) {
      ExamItem entity = new ExamItem();
      entity.setExamId(exam.getId());
      entity.setItemCode(item.itemCode());
      entity.setItemName(item.itemName() == null ? "" : item.itemName());
      entity.setValueNum(item.valueNum());
      entity.setValueText(item.valueText() == null ? "" : item.valueText());
      entity.setUnit(item.unit() == null ? "" : item.unit());
      examItemMapper.insert(entity);
      Map<String, Object> row = new HashMap<>();
      row.put("itemCode", entity.getItemCode());
      row.put("valueNum", entity.getValueNum());
      row.put("valueText", entity.getValueText());
      saved.add(row);
    }
    auditService.record("EXAM", String.valueOf(exam.getId()), operatorId, "创建体检记录", "明细 " + saved.size() + " 项", 0);
    Map<String, Object> result = new HashMap<>();
    result.put("examId", exam.getId());
    result.put("status", exam.getStatus());
    result.put("items", saved.size());
    return result;
  }

  /** 读取体检记录与明细（含人员快照），供判定组装输入快照。 */
  public ExamSnapshot snapshot(Long examId) {
    Exam exam = examMapper.selectById(examId);
    if (exam == null) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "体检记录不存在");
    }
    Person person = personMapper.selectById(exam.getPersonId());
    var items = examItemMapper.selectList(
        new LambdaQueryWrapper<ExamItem>().eq(ExamItem::getExamId, examId));
    return new ExamSnapshot(exam, person, items);
  }

  /** 体检快照：判定输入的只读装配。 */
  public record ExamSnapshot(Exam exam, Person person, List<ExamItem> items) {}
}
