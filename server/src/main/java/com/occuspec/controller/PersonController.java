package com.occuspec.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.Idempotency;
import com.occuspec.dto.PersonCreateRequest;
import com.occuspec.entity.Person;
import com.occuspec.service.PersonService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 体检对象控制器：只做参数校验与 DTO 装配。
 */
@RestController
@RequestMapping("/persons")
public class PersonController {
  private final PersonService personService;

  public PersonController(PersonService personService) {
    this.personService = personService;
  }

  /** 创建体检对象。 */
  @PostMapping
  @Idempotency
  @AuditLog(action = "创建体检对象")
  public ApiResponse<Map<String, Object>> create(@Valid @RequestBody PersonCreateRequest request) {
    String operator = StpUtil.isLogin() ? String.valueOf(StpUtil.getLoginId()) : "";
    return ApiResponse.ok(personService.create(request, operator));
  }

  /** 按姓名模糊查询。 */
  @GetMapping
  public ApiResponse<Map<String, Object>> search(@RequestParam(required = false) String keyword) {
    List<Person> persons = personService.search(keyword);
    return ApiResponse.ok(Map.of("data", persons.stream().map(p -> Map.of(
        "personId", p.getId(), "name", p.getName(), "gender", p.getGender() == null ? "" : p.getGender(),
        "company", p.getCompany() == null ? "" : p.getCompany(),
        "jobType", p.getJobType() == null ? "" : p.getJobType())).toList()));
  }
}
