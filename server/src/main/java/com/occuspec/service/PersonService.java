package com.occuspec.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.common.AuditLog;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.dto.PersonCreateRequest;
import com.occuspec.entity.Person;
import com.occuspec.mapper.PersonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 体检对象服务：证件号只存哈希，不存明文。
 */
@Service
public class PersonService {
  private final PersonMapper personMapper;
  private final AuditService auditService;

  public PersonService(PersonMapper personMapper, AuditService auditService) {
    this.personMapper = personMapper;
    this.auditService = auditService;
  }

  /** 创建体检对象。 */
  @AuditLog(action = "创建体检对象")
  public Map<String, Object> create(PersonCreateRequest request, String operatorId) {
    Person person = new Person();
    person.setName(request.name());
    person.setIdCardHash(sha256(request.idCard() == null ? "" : request.idCard()));
    person.setGender(request.gender() == null ? "" : request.gender());
    person.setBirthDate(request.birthDate());
    person.setCompany(request.company() == null ? "" : request.company());
    person.setJobType(request.jobType() == null ? "" : request.jobType());
    person.setExposureHistory(toJsonArray(request.exposureHistory()));
    personMapper.insert(person);
    auditService.record("PERSON", String.valueOf(person.getId()), operatorId, "创建体检对象", person.getName(), 0);
    Map<String, Object> result = new HashMap<>();
    result.put("personId", person.getId());
    return result;
  }

  /** 按姓名模糊查询。 */
  public java.util.List<Person> search(String keyword) {
    var wrapper = new LambdaQueryWrapper<Person>();
    if (keyword != null && !keyword.isBlank()) {
      wrapper.like(Person::getName, keyword);
    }
    wrapper.orderByDesc(Person::getId).last("LIMIT 50");
    return personMapper.selectList(wrapper);
  }

  private String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "哈希失败");
    }
  }

  /** 接害史统一存 JSON 数组：纯文本转单元素数组，已是 JSON 则原样保留。 */
  private String toJsonArray(String raw) {
    if (raw == null || raw.isBlank()) {
      return "[]";
    }
    String text = raw.trim();
    if (text.startsWith("[") || text.startsWith("{")) {
      return text;
    }
    return "[\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
  }
}
