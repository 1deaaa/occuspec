package com.occuspec.service;

import com.occuspec.entity.BatchTask;
import com.occuspec.enums.BatchTaskStatus;
import com.occuspec.mapper.BatchTaskMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 批量任务服务：数据库任务表 + 定时轮询，不引入消息队列。
 * 上传 CSV 创建任务，定时任务加分布式锁后串行执行，结果可导出 Excel。
 */
@Service
public class BatchTaskService {
  private static final Logger log = LoggerFactory.getLogger(BatchTaskService.class);
  private final BatchTaskMapper batchTaskMapper;
  private final DistributedLock distributedLock;
  private final AssessService assessService;
  private final java.util.concurrent.Executor batchExecutor;

  public BatchTaskService(
      BatchTaskMapper batchTaskMapper, DistributedLock distributedLock,
      AssessService assessService, @Qualifier("batchExecutor") java.util.concurrent.Executor batchExecutor) {
    this.batchTaskMapper = batchTaskMapper;
    this.distributedLock = distributedLock;
    this.assessService = assessService;
    this.batchExecutor = batchExecutor;
  }

  /** 创建批量任务（CSV 内容已解析为 examId 列表，此处简化存文件路径）。 */
  public BatchTask create(String type, String fileUrl, List<Long> examIds, String creatorId) {
    BatchTask task = new BatchTask();
    task.setType(type);
    task.setFileUrl(fileUrl == null ? "" : fileUrl);
    task.setStatus(BatchTaskStatus.PENDING.name());
    task.setTotal(examIds == null ? 0 : examIds.size());
    task.setSuccess(0);
    task.setFail(0);
    task.setErrorUrl(examIds == null ? "" : examIds.toString());
    task.setCreatorId(creatorId == null ? "" : creatorId);
    batchTaskMapper.insert(task);
    return task;
  }

  /** 定时轮询：每 30 秒扫一次待执行任务，分布式锁防重复执行。 */
  @Scheduled(fixedDelay = 30000)
  public void poll() {
    String result = distributedLock.tryRun("occuspec:lock:batch-poll", 60, () -> {
      var pending = batchTaskMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BatchTask>()
          .eq(BatchTask::getStatus, BatchTaskStatus.PENDING.name())
          .orderByAsc(BatchTask::getId)
          .last("LIMIT 5"));
      for (BatchTask task : pending) {
        batchExecutor.execute(() -> run(task.getId()));
      }
      return "ok";
    });
    if (result == null) {
      log.debug("批量轮询跳过：未拿到锁");
    }
  }

  /** 执行单个任务：解析 examId 列表逐条判定，更新成功失败计数。 */
  public void run(long taskId) {
    BatchTask task = batchTaskMapper.selectById(taskId);
    if (task == null || !BatchTaskStatus.PENDING.name().equals(task.getStatus())) {
      return;
    }
    task.setStatus(BatchTaskStatus.RUNNING.name());
    task.setUpdatedAt(LocalDateTime.now());
    batchTaskMapper.updateById(task);
    int success = 0;
    int fail = 0;
    try {
      for (Long examId : parseExamIds(task.getErrorUrl())) {
        try {
          assessService.assess(examId, null, null);
          success++;
        } catch (Exception ex) {
          fail++;
          log.warn("批量判定失败 task={} exam={} err={}", taskId, examId, ex.getMessage());
        }
      }
      task.setStatus(BatchTaskStatus.SUCCESS.name());
    } catch (Exception ex) {
      task.setStatus(BatchTaskStatus.FAILED.name());
      log.warn("批量任务失败 task={} err={}", taskId, ex.getMessage());
    }
    task.setSuccess(success);
    task.setFail(fail);
    task.setUpdatedAt(LocalDateTime.now());
    batchTaskMapper.updateById(task);
  }

  /** 导出 Excel：任务结果汇总。 */
  public Workbook exportXlsx(long taskId) {
    BatchTask task = batchTaskMapper.selectById(taskId);
    Workbook workbook = new XSSFWorkbook();
    Sheet sheet = workbook.createSheet("批量结果");
    Row head = sheet.createRow(0);
    head.createCell(0).setCellValue("任务ID");
    head.createCell(1).setCellValue("类型");
    head.createCell(2).setCellValue("状态");
    head.createCell(3).setCellValue("总数");
    head.createCell(4).setCellValue("成功");
    head.createCell(5).setCellValue("失败");
    if (task != null) {
      Row row = sheet.createRow(1);
      row.createCell(0).setCellValue(task.getId());
      row.createCell(1).setCellValue(task.getType());
      row.createCell(2).setCellValue(task.getStatus());
      row.createCell(3).setCellValue(task.getTotal() == null ? 0 : task.getTotal());
      row.createCell(4).setCellValue(task.getSuccess() == null ? 0 : task.getSuccess());
      row.createCell(5).setCellValue(task.getFail() == null ? 0 : task.getFail());
    }
    return workbook;
  }

  private List<Long> parseExamIds(String raw) {
    List<Long> ids = new java.util.ArrayList<>();
    if (raw == null || raw.isBlank()) {
      return ids;
    }
    for (String part : raw.replace("[", "").replace("]", "").split(",")) {
      try {
        ids.add(Long.parseLong(part.trim()));
      } catch (NumberFormatException ignored) {
        // 跳过非法片段
      }
    }
    return ids;
  }
}
