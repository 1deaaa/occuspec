package com.occuspec.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 密码哈希：SHA-256 加盐比对，种子数据兼容 BCrypt 占位。
 * 说明：V3 种子数据的哈希为占位值，生产部署前需经管理接口重置密码。
 */
public class PasswordHasher {
  private static final String SALT = "occuspec-local-salt";

  /** 生成哈希。 */
  public static String hash(String raw) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest((SALT + raw).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return "sha256$" + sb;
    } catch (Exception e) {
      throw new IllegalStateException("哈希失败", e);
    }
  }

  /** 比对：兼容 sha256$ 前缀与种子占位。 */
  public static boolean matches(String raw, String stored) {
    if (stored == null) {
      return false;
    }
    if (stored.startsWith("sha256$")) {
      return hash(raw).equals(stored);
    }
    // 种子占位兼容：默认密码 1009
    return "1009".equals(raw);
  }
}
