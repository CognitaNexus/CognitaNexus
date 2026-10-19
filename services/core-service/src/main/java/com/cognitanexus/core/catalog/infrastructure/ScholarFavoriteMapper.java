package com.cognitanexus.core.catalog.infrastructure;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 学者收藏的持久化。 */
@Mapper
public interface ScholarFavoriteMapper {

  record FavoriteRow(
      String scholarId, String displayName, String organizationName, Instant favoritedAt) {}

  /** 重复收藏被主键吸收，返回 0 行且不报错——收藏是幂等动作。 */
  @Insert(
      """
      INSERT INTO core_scholar_favorite (account_id, scholar_id)
      VALUES (#{accountId}, #{scholarId})
      ON CONFLICT (account_id, scholar_id) DO NOTHING
      """)
  int insert(@Param("accountId") String accountId, @Param("scholarId") String scholarId);

  @Delete(
      """
      DELETE FROM core_scholar_favorite
      WHERE account_id = #{accountId} AND scholar_id = #{scholarId}
      """)
  int delete(@Param("accountId") String accountId, @Param("scholarId") String scholarId);

  @Select(
      """
      SELECT f.scholar_id AS scholarId, s.display_name AS displayName,
             s.organization_name AS organizationName, f.created_at AS favoritedAt
      FROM core_scholar_favorite f
      JOIN core_scholar s ON s.id = f.scholar_id
      WHERE f.account_id = #{accountId}
        AND s.merged_into_id IS NULL
        AND s.data_nature = 'REAL'
      ORDER BY f.created_at DESC, f.scholar_id
      LIMIT #{limit}
      """)
  List<FavoriteRow> listByAccount(@Param("accountId") String accountId, @Param("limit") int limit);

  @Select("SELECT count(*) FROM core_scholar_favorite WHERE account_id = #{accountId}")
  long countByAccount(@Param("accountId") String accountId);

  @Select(
      """
      SELECT count(*) FROM core_scholar_favorite f
      JOIN core_scholar s ON s.id = f.scholar_id
      WHERE f.scholar_id = #{scholarId} AND s.merged_into_id IS NULL
      """)
  long countByScholar(@Param("scholarId") String scholarId);

  @Select(
      """
      SELECT count(*) FROM core_scholar_favorite
      WHERE account_id = #{accountId} AND scholar_id = #{scholarId}
      """)
  long exists(@Param("accountId") String accountId, @Param("scholarId") String scholarId);
}
