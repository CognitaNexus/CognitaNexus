package com.cognitanexus.core.identity.infrastructure;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 会话撤销登记持久化。
 *
 * <p>依据：架构 V0.2 §10.2。退出先持久登记撤销、再清 Redis 会话；顺序反过来会在 Redis 恢复旧数据
 * 时让已退出的会话复活。只保存会话标识的不可逆摘要，不保存会话标识本身。
 */
@Mapper
public interface SessionRevocationMapper {

  @Insert(
      """
      INSERT INTO iam_session_revocation (session_id_hash, account_id, revoked_at, reason)
      VALUES (#{sessionIdHash}, #{accountId}, now(), #{reason})
      ON CONFLICT (session_id_hash) DO NOTHING
      """)
  int register(
      @Param("sessionIdHash") String sessionIdHash,
      @Param("accountId") String accountId,
      @Param("reason") String reason);

  @Select("SELECT EXISTS (SELECT 1 FROM iam_session_revocation WHERE session_id_hash = #{hash})")
  boolean exists(@Param("hash") String sessionIdHash);

  /**
   * 清理早于保留窗口的撤销记录。
   *
   * <p>保留窗口必须长于会话最大寿命，否则一条早于窗口但仍在 Redis 中存活的会话会重新可用。
   */
  @Delete(
      """
      DELETE FROM iam_session_revocation
      WHERE revoked_at < now() - (interval '1 day' * #{retentionDays})
      """)
  int purgeExpired(@Param("retentionDays") int retentionDays);
}
