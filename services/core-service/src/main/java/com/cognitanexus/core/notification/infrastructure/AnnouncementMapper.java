package com.cognitanexus.core.notification.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 系统公告的发布台账。
 *
 * <p>本表只写不改：公告发出后无法撤回（它已经被复制进每个账号的通知里）。要更正只能再发一条。
 * 这是 ADR-003「通知只追加」的直接推论，不是遗漏。
 */
@Mapper
public interface AnnouncementMapper {

  record AnnouncementRow(
      String id,
      String title,
      String body,
      String level,
      String publishedBy,
      String publishedByName,
      Instant publishedAt,
      int recipients) {}

  @Insert(
      """
      INSERT INTO core_announcement (id, title, body, level, published_by, recipients)
      VALUES (#{id}, #{title}, #{body}, #{level}, #{publishedBy}, #{recipients})
      """)
  void insert(
      @Param("id") String id,
      @Param("title") String title,
      @Param("body") String body,
      @Param("level") String level,
      @Param("publishedBy") String publishedBy,
      @Param("recipients") int recipients);

  /**
   * 最近 N 分钟内是否有同标题公告。
   *
   * <p>用途是防手滑连点：管理员双击「发布」时，后台会在几百毫秒内收到两个请求，
   * 第二个应该被挡掉，而不是给全体账号各发两条一模一样的通知。
   */
  @Select(
      """
      SELECT count(*) FROM core_announcement
      WHERE title = #{title} AND published_at > now() - make_interval(secs => #{seconds})
      """)
  long countRecentByTitle(@Param("title") String title, @Param("seconds") int seconds);

  @Select(
      """
      SELECT a.id, a.title, a.body, a.level, a.published_by AS publishedBy,
             COALESCE(i.display_name, a.published_by) AS publishedByName,
             a.published_at AS publishedAt, a.recipients
      FROM core_announcement a
      LEFT JOIN iam_account i ON i.id = a.published_by
      ORDER BY a.published_at DESC, a.id DESC
      LIMIT #{limit}
      """)
  List<AnnouncementRow> list(@Param("limit") int limit);
}
