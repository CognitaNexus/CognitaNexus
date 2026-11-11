package com.cognitanexus.core.identity.application;

import com.cognitanexus.sharedkernel.BusinessException;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 口令强度校验。
 *
 * <p>依据：架构 V0.2 §10.2。口令使用框架支持的自适应哈希（BCrypt），强度参数由耗时测试确定。
 * 本类只做前置的基本强度检查，不宣称已通过安全评审；拒绝时不回显口令内容。
 */
@Component
public class PasswordPolicy {

  private static final int MIN_LENGTH = 10;
  private static final int MAX_LENGTH = 128;
  private static final Set<String> COMMON_WEAK =
      Set.of("password", "12345678", "qwertyui", "11111111", "admin1234", "letmein123");

  public void validate(String raw) {
    if (raw == null || raw.length() < MIN_LENGTH) {
      throw BusinessException.invalidArgument("口令长度至少 " + MIN_LENGTH + " 位");
    }
    if (raw.length() > MAX_LENGTH) {
      throw BusinessException.invalidArgument("口令长度不得超过 " + MAX_LENGTH + " 位");
    }
    boolean hasLetter = raw.chars().anyMatch(Character::isLetter);
    boolean hasDigit = raw.chars().anyMatch(Character::isDigit);
    if (!hasLetter || !hasDigit) {
      throw BusinessException.invalidArgument("口令须同时包含字母与数字");
    }
    String lowered = raw.toLowerCase(java.util.Locale.ROOT);
    if (COMMON_WEAK.contains(lowered)) {
      throw BusinessException.invalidArgument("该口令过于常见，请更换");
    }
  }
}
