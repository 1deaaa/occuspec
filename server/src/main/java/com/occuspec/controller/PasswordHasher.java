package com.occuspec.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 密码哈希：SHA-256 加盐比对。
 * 盐值固定在本类，种子数据的哈希由同一算法生成，生产部署后请重置密码。
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

  /** 比对：存储格式必须为 sha256$ 前缀。 */
  public static boolean matches(String raw, String stored) {
    if (stored == null || raw == null) {
      return false;
    }
    if (!stored.startsWith("sha256$")) {
      return false;
    }
    return hash(raw).equals(stored);
  }
}
