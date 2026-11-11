package com.cognitanexus.core.platform.event;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 目录修订计数器。
 *
 * <p>依据：架构 V0.2 §6.3。计数器是单行表，事务内 <code>UPDATE ... RETURNING</code> 会持有该行锁
 * 直到提交，因此修订序号与提交顺序一致；不得用普通序列的最大值假设「此前事务均已提交」。
 */
@Mapper
public interface CatalogRevisionMapper {

  @Select("SELECT current_revision FROM evt_revision_counter WHERE id = 1")
  long currentRevision();

  @Select(
      """
      UPDATE evt_revision_counter
      SET current_revision = current_revision + 1, updated_at = now()
      WHERE id = 1
      RETURNING current_revision
      """)
  long bumpRevision();

  @Select("SELECT required_public_revision FROM evt_revision_counter WHERE id = 1")
  long requiredPublicRevision();

  /**
   * 把权威公开水位推进到当前修订值。
   *
   * <p>隐私收紧变更与业务在同事务调用；query 读到该值大于自身已发布水位时返回 503 VIEW_UPDATING。
   */
  @Select(
      """
      UPDATE evt_revision_counter
      SET required_public_revision = current_revision, updated_at = now()
      WHERE id = 1
      RETURNING required_public_revision
      """)
  long tightenPublicVisibility();
}
