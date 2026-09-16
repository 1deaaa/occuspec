package com.occuspec.service;

import com.occuspec.common.TraceIdFilter;
import com.occuspec.entity.Audit;
import com.occuspec.mapper.AuditMapper;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 审计写入服务：只追加不修改，失败只记日志不抛穿。
 */
@Service
public class AuditService {
  private final AuditMapper auditMapper;

  public AuditService(AuditMapper auditMapper) {
    this.auditMapper = auditMapper;
  }

  /** 记录一条审计。 */
  public void record(String bizType, String bizId, String actorId, String action, String diff, long costMs) {
    try {
      Audit audit = new Audit();
      audit.setBizType(bizType);
      audit.setBizId(bizId == null ? "" : bizId);
      audit.setActorId(actorId == null ? "" : actorId);
      audit.setAction(action == null ? "" : action);
      audit.setDiff(diff == null ? "" : diff.length() > 4000 ? diff.substring(0, 4000) : diff);
      String traceId = MDC.get(TraceIdFilter.TRACE_ID);
      audit.setTraceId(traceId == null ? "" : traceId);
      audit.setCostMs(costMs);
      auditMapper.insert(audit);
    } catch (Exception ignored) {
      // 审计失败不得影响主流程
    }
  }
}
