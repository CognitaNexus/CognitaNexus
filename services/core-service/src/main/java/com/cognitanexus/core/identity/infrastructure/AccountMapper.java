package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountCredentials;
import com.cognitanexus.core.identity.domain.AccountStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 账号持久化。
 *
 * <p>依据：编码规范 §5.1。必须使用参数绑定 #{}；禁止 SELECT *；跨表查询明确基数。
 */
@Mapper
public interface AccountMapper {

  /** 账号行。 */
  record AccountRow(
      String id,
      String username,
      String usernameNorm,
      String passwordHash,
      String displayName,
      String status,
      long authVersion,
      int failedLoginAttempts,
      Instant lockedUntil,
      boolean mustChangePassword) {}

  @Select(
      """
      SELECT id, username, username_norm, password_hash, display_name, status, auth_version,
             failed_login_attempts, locked_until, must_change_password
      FROM iam_account WHERE id = #{id}
      """)
  Optional<AccountRow> findById(@Param("id") String id);

  @Select(
      """
      SELECT id, username, username_norm, password_hash, display_name, status, auth_version,
             failed_login_attempts, locked_until, must_change_password
      FROM iam_account WHERE username_norm = #{usernameNorm}
      """)
  Optional<AccountRow> findByUsernameNorm(@Param("usernameNorm") String usernameNorm);

  @Select(
      """
      SELECT id, username, username_norm, password_hash, display_name, status, auth_version,
             failed_login_attempts, locked_until, must_change_password
      FROM iam_account ORDER BY created_at, id LIMIT #{limit}
      """)
  List<AccountRow> listAll(@Param("limit") int limit);

  /**
   * 启用账号的 ID 列表。
   *
   * <p>只取 ACTIVE：停用账号登录不了，给它写通知既浪费又会造成「这个账号还在接收消息」的错觉。
   * 带 limit 不是分页，是防呆——公告广播一次写 N 条通知，N 必须有界。
   */
  @Select(
      """
      SELECT id FROM iam_account WHERE status = 'ACTIVE' ORDER BY created_at, id LIMIT #{limit}
      """)
  List<String> listActiveAccountIds(@Param("limit") int limit);

  @Insert(
      """
      INSERT INTO iam_account
        (id, username, username_norm, password_hash, display_name, status, auth_version)
      VALUES
        (#{id}, #{username}, #{usernameNorm}, #{passwordHash}, #{displayName}, 'ACTIVE', 1)
      ON CONFLICT (username_norm) DO NOTHING
      """)
  int insert(
      @Param("id") String id,
      @Param("username") String username,
      @Param("usernameNorm") String usernameNorm,
      @Param("passwordHash") String passwordHash,
      @Param("displayName") String displayName);

  /**
   * 停用账号并提升认证版本。
   *
   * <p>提升 authVersion 使既有会话即使仍存在于 Redis 也不可用（架构 V0.2 §10.2）。
   * 条件 status='ACTIVE' 使重复停用不产生额外的版本提升。
   */
  @Update(
      """
      UPDATE iam_account
      SET status = 'DISABLED', auth_version = auth_version + 1, updated_at = now()
      WHERE id = #{accountId} AND status = 'ACTIVE'
      """)
  int disable(@Param("accountId") String accountId);

  @Insert(
      """
      INSERT INTO iam_account_role (account_id, role) VALUES (#{accountId}, #{role})
      ON CONFLICT (account_id, role) DO NOTHING
      """)
  int insertRole(@Param("accountId") String accountId, @Param("role") String role);

  @Delete("DELETE FROM iam_account_role WHERE account_id = #{accountId} AND role = #{role}")
  int deleteRole(@Param("accountId") String accountId, @Param("role") String role);

  /**
   * 仅提升认证版本，不动账号状态。
   *
   * <p>角色变化必须走这条路：会话里存的是登录那一刻的角色集合，不提升版本的话，
   * 被撤销 REVIEWER 的人在当前会话里仍然是审核员，能一直用到自己退出为止——
   * 这不是缓存陈旧，是权限没有真正收回。
   */
  @Update(
      """
      UPDATE iam_account SET auth_version = auth_version + 1, updated_at = now()
      WHERE id = #{accountId}
      """)
  int bumpAuthVersion(@Param("accountId") String accountId);

  /**
   * 改口令：同时提升认证版本，使本人其他会话与管理员重置前的会话全部失效。
   *
   * <p>为什么要在这里 +1 而不是由调用方再调一次 bumpAuthVersion：两条语句之间若进程退出，
   * 会留下「已换口令但旧会话仍可用」的窗口。会话有效性以 auth_version 为唯一判据（架构 §10.2），
   * 所以必须与口令写在同一条 UPDATE 里。
   *
   * <p>条件 status = 'ACTIVE'：停用账号不接受改密，管理员应先恢复账号。
   */
  @Update(
      """
      UPDATE iam_account
         SET password_hash = #{passwordHash},
             must_change_password = #{mustChangePassword},
             password_changed_at = now(),
             updated_at = now(),
             auth_version = auth_version + 1
       WHERE id = #{accountId} AND status = 'ACTIVE'
      """)
  int updatePassword(
      @Param("accountId") String accountId,
      @Param("passwordHash") String passwordHash,
      @Param("mustChangePassword") boolean mustChangePassword);

  @Select("SELECT role FROM iam_account_role WHERE account_id = #{accountId} ORDER BY role")
  Set<String> findRoles(@Param("accountId") String accountId);

  /**
   * 统计持有指定角色的账号数。
   *
   * <p>仅用于平台引导判定「是否已存在平台管理员」。不返回账号明细，避免把引导流程变成
   * 绕过授权体系的账号枚举入口。
   */
  @Select(
      """
      SELECT count(*) FROM iam_account_role r
      JOIN iam_account a ON a.id = r.account_id
      WHERE r.role = #{role} AND a.status = 'ACTIVE'
      """)
  long countActiveByRole(@Param("role") String role);

  @Update(
      """
      UPDATE iam_account
      SET failed_login_attempts = 0, locked_until = NULL, last_login_at = now(), updated_at = now()
      WHERE id = #{accountId}
      """)
  int recordLoginSuccess(@Param("accountId") String accountId);

  /**
   * 记一次登录失败并在达到阈值时锁定。
   *
   * <p>失败计数与锁定判断在一条语句内完成，避免「先读后写」在并发爆破下互相覆盖。
   */
  @Update(
      """
      UPDATE iam_account
      SET failed_login_attempts = failed_login_attempts + 1,
          locked_until = CASE
            WHEN failed_login_attempts + 1 >= #{lockThreshold}
            THEN now() + (interval '1 second' * #{lockSeconds})
            ELSE locked_until
          END,
          updated_at = now()
      WHERE id = #{accountId}
      """)
  int recordLoginFailure(
      @Param("accountId") String accountId,
      @Param("lockThreshold") int lockThreshold,
      @Param("lockSeconds") int lockSeconds);

  @Select("SELECT now()")
  Instant now();

  default Account toDomain(AccountRow row, Set<String> roles) {
    return new Account(
        row.id(),
        row.username(),
        row.usernameNorm(),
        row.passwordHash(),
        row.displayName(),
        AccountStatus.of(row.status()),
        row.authVersion(),
        roles);
  }

  default AccountCredentials toCredentials(AccountRow row, Set<String> roles) {
    return new AccountCredentials(
        row.id(),
        row.username(),
        row.usernameNorm(),
        row.passwordHash(),
        row.displayName(),
        AccountStatus.of(row.status()),
        row.authVersion(),
        roles,
        row.failedLoginAttempts(),
        row.lockedUntil(),
        row.mustChangePassword());
  }
}
