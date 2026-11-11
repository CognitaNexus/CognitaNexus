package com.cognitanexus.core.notification.infrastructure;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 通知持久化。所有读取与更新都带 account_id 条件：横向越权在 SQL 层就被挡住，
 * 不依赖服务层记住传参。
 *
 * <p>V20 之后所有读方法都排除 {@code archived_at IS NOT NULL}：归档是用户视角的「删除」，
 * 行仍然在库里供审计，但对该用户不可见。任何新加的读方法都必须带这个条件，否则会出现
 * 「移除后还在列表里」——这类问题在测试里很难发现，因为默认数据都是未归档的行。
 */
@Mapper
public interface NotificationMapper {

  record NotificationRow(
      String id,
      String accountId,
      String kind,
      String title,
      String body,
      String refType,
      String refId,
      Instant createdAt,
      Instant readAt) {}

  @Insert(
      """
      INSERT INTO core_notification
        (id, account_id, kind, title, body, ref_type, ref_id, created_at)
      VALUES
        (#{id}, #{accountId}, #{kind}, #{title}, #{body}, #{refType}, #{refId}, now())
      """)
  void insert(
      @Param("id") String id,
      @Param("accountId") String accountId,
      @Param("kind") String kind,
      @Param("title") String title,
      @Param("body") String body,
      @Param("refType") String refType,
      @Param("refId") String refId);

  @Select(
      """
      <script>
      SELECT id AS id, account_id AS accountId, kind AS kind, title AS title, body AS body,
             ref_type AS refType, ref_id AS refId, created_at AS createdAt, read_at AS readAt
      FROM core_notification
      WHERE account_id = #{accountId}
        AND archived_at IS NULL
      <if test="unreadOnly">AND read_at IS NULL</if>
      <if test="kind != null">AND kind = #{kind}</if>
      ORDER BY created_at DESC, id DESC
      LIMIT #{limit} OFFSET #{offset}
      </script>
      """)
  List<NotificationRow> listByAccount(
      @Param("accountId") String accountId,
      @Param("unreadOnly") boolean unreadOnly,
      @Param("kind") String kind,
      @Param("limit") int limit,
      @Param("offset") int offset);

  @Select(
      """
      SELECT count(*) FROM core_notification
      WHERE account_id = #{accountId} AND read_at IS NULL AND archived_at IS NULL
      """)
  long countUnread(@Param("accountId") String accountId);

  /**
   * 标记已读。LIMIT 硬写在 SQL 里而不是靠调用方截断 ids：即使调用方漏了上限，
   * 一次操作波及的行数也有界。
   */
  @Update(
      """
      <script>
      UPDATE core_notification
      SET read_at = now()
      WHERE account_id = #{accountId}
        AND read_at IS NULL
        AND archived_at IS NULL
        AND id IN (
          SELECT id FROM core_notification
          WHERE account_id = #{accountId} AND read_at IS NULL AND archived_at IS NULL
            AND id IN
            <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
          LIMIT #{limit}
        )
      </script>
      """)
  int markRead(
      @Param("accountId") String accountId,
      @Param("ids") Collection<String> ids,
      @Param("limit") int limit);

  @Update(
      """
      UPDATE core_notification
      SET read_at = now()
      WHERE account_id = #{accountId} AND read_at IS NULL AND archived_at IS NULL
      """)
  int markAllRead(@Param("accountId") String accountId);

  /**
   * 移除（归档）。
   *
   * <p>不区分已读未读：用户点「移除」就是对这一条做处置，拒绝反而更烦人。前端对未读行的
   * 移除按钮会先提示一次「未读，移除后不再出现在列表里」，把决定权交回用户。
   */
  @Update(
      """
      <script>
      UPDATE core_notification
      SET archived_at = now()
      WHERE account_id = #{accountId}
        AND archived_at IS NULL
        AND id IN (
          SELECT id FROM core_notification
          WHERE account_id = #{accountId} AND archived_at IS NULL
            AND id IN
            <foreach collection="ids" item="item" open="(" separator="," close=")">#{item}</foreach>
          LIMIT #{limit}
        )
      </script>
      """)
  int archive(
      @Param("accountId") String accountId,
      @Param("ids") Collection<String> ids,
      @Param("limit") int limit);

  @Update(
      """
      UPDATE core_notification
      SET archived_at = now()
      WHERE account_id = #{accountId} AND archived_at IS NULL AND read_at IS NOT NULL
      """)
  int archiveAllRead(@Param("accountId") String accountId);

  @Select(
      """
      SELECT kind FROM core_notification
      WHERE account_id = #{accountId} AND archived_at IS NULL
      GROUP BY kind
      ORDER BY count(*) DESC, kind
      """)
  List<String> kindsOf(@Param("accountId") String accountId);
}
