package com.occuspec.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.occuspec.common.ApiResponse;
import com.occuspec.common.AuditLog;
import com.occuspec.common.BusinessException;
import com.occuspec.common.ErrorCode;
import com.occuspec.dto.LoginRequest;
import com.occuspec.entity.SysUser;
import com.occuspec.mapper.SysUserMapper;
import com.occuspec.service.AuditService;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权控制器：登录签发会话、登出销毁会话。
 * 密码校验使用应用层哈希比对，会话存 Redis。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {
  private final SysUserMapper sysUserMapper;
  private final AuditService auditService;

  public AuthController(SysUserMapper sysUserMapper, AuditService auditService) {
    this.sysUserMapper = sysUserMapper;
    this.auditService = auditService;
  }

  /** 登录：成功返回 token 与用户信息。 */
  @PostMapping("/login")
  @AuditLog(action = "用户登录")
  public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
    SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
        .eq(SysUser::getUsername, request.username())
        .last("LIMIT 1"));
    if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
      throw new BusinessException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
    }
    if (!PasswordHasher.matches(request.password(), user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.AUTH_FAILED, "用户名或密码错误");
    }
    StpUtil.login(user.getId());
    String token = StpUtil.getTokenValue();
    auditService.record("USER", String.valueOf(user.getId()), String.valueOf(user.getId()), "用户登录", "", 0);
    Map<String, Object> data = new HashMap<>();
    data.put("token", token);
    data.put("userId", user.getId());
    data.put("username", user.getUsername());
    data.put("nickname", user.getNickname());
    data.put("role", user.getRole());
    return ApiResponse.ok(data);
  }

  /** 登出：销毁当前会话。 */
  @PostMapping("/logout")
  public ApiResponse<Map<String, Object>> logout() {
    if (StpUtil.isLogin()) {
      Object loginId = StpUtil.getLoginId();
      StpUtil.logout();
      auditService.record("USER", String.valueOf(loginId), String.valueOf(loginId), "用户登出", "", 0);
    }
    return ApiResponse.ok(Map.of("ok", true));
  }
}
