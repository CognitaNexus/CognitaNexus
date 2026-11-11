package com.cognitanexus.core.integrity.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 学术诚信声明的持久化。
 *
 * <p>依据：架构 V0.2 §5.2、迁移 {@code V13__achievement_integrity_notice.sql}。
 *
 * <p>该表只追加与撤销：没有 UPDATE 任意字段的语句，{@link #revoke} 是唯一允许改变行内容的入口，
 * 且它只写撤销三件套并附带 {@code status = 'ACTIVE'} 前置条件。
 */
@Mapper
public interface IntegrityMapper {

  record NoticeRow(
      String id,
      String achievementId,
      String noticeType,
      String status,
      String declaredBy,
      String reason,
      String sourceUrl,
      LocalDate announcedOn,
      String recordedBy,
      Instant recordedAt,
      String revokedBy,
      Instant revokedAt,
      String revokeReason) {}

  @Insert(
      """
      INSERT INTO core_achievement_integrity_notice
        (id, achievement_id, notice_type, status, declared_by, reason, source_url, announced_on,
         recorded_by, recorded_at)
      VALUES
        (#{id}, #{achievementId}, #{noticeType}, #{status}, #{declaredBy}, #{reason},
         #{sourceUrl}, #{announcedOn}, #{recordedBy}, #{recordedAt})
      """)
  int insert(NoticeRow row);

  @Select(
      """
      SELECT id, achievement_id, notice_type, status, declared_by, reason, source_url, announced_on,
             recorded_by, recorded_at, revoked_by, revoked_at, revoke_reason
      FROM core_achievement_integrity_notice
      WHERE id = #{id}
      """)
  Optional<NoticeRow> findById(@Param("id") String id);

  @Select(
      """
      SELECT id, achievement_id, notice_type, status, declared_by, reason, source_url, announced_on,
             recorded_by, recorded_at, revoked_by, revoked_at, revoke_reason
      FROM core_achievement_integrity_notice
      WHERE achievement_id = #{achievementId}
      ORDER BY recorded_at DESC, id DESC
      """)
  List<NoticeRow> findByAchievement(@Param("achievementId") String achievementId);

  @Select(
      """
      SELECT id, achievement_id, notice_type, status, declared_by, reason, source_url, announced_on,
             recorded_by, recorded_at, revoked_by, revoked_at, revoke_reason
      FROM core_achievement_integrity_notice
      WHERE achievement_id = #{achievementId} AND notice_type = #{noticeType} AND status = 'ACTIVE'
      """)
  Optional<NoticeRow> findActive(
      @Param("achievementId") String achievementId, @Param("noticeType") String noticeType);

  @Update(
      """
      UPDATE core_achievement_integrity_notice
      SET status = 'REVOKED', revoked_by = #{revokedBy}, revoked_at = #{revokedAt},
          revoke_reason = #{reason}
      WHERE id = #{id} AND status = 'ACTIVE'
      """)
  int revoke(
      @Param("id") String id,
      @Param("revokedBy") String revokedBy,
      @Param("revokedAt") Instant revokedAt,
      @Param("reason") String reason);

  @Select(
      """
      <script>
      SELECT id, achievement_id, notice_type, status, declared_by, reason, source_url, announced_on,
             recorded_by, recorded_at, revoked_by, revoked_at, revoke_reason
      FROM core_achievement_integrity_notice
      <where>
        <if test="activeOnly">status = 'ACTIVE'</if>
      </where>
      ORDER BY recorded_at DESC, id DESC
      LIMIT #{limit}
      </script>
      """)
  List<NoticeRow> listRecent(@Param("limit") int limit, @Param("activeOnly") boolean activeOnly);
}
