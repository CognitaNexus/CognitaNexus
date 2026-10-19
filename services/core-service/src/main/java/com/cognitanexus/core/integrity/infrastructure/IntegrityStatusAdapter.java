package com.cognitanexus.core.integrity.infrastructure;

import com.cognitanexus.core.catalog.application.AchievementIntegrityPort;
import com.cognitanexus.core.integrity.application.IntegrityNoticeRepository;
import com.cognitanexus.core.integrity.domain.IntegritySummary;
import org.springframework.stereotype.Repository;

/**
 * 诚信状态读取端口的实现。
 *
 * <p>刻意只依赖仓储、不依赖 {@code IntegrityNoticeService}：目录侧的外发组件需要这个端口，而
 * 诚信服务登记声明时又需要外发组件。若这里注入服务，三个 Bean 会构成构造器循环依赖，
 * 表现为应用启动失败而不是某条用例失败。
 */
@Repository
public class IntegrityStatusAdapter implements AchievementIntegrityPort {

  private final IntegrityNoticeRepository notices;

  public IntegrityStatusAdapter(IntegrityNoticeRepository notices) {
    this.notices = notices;
  }

  @Override
  public String statusOf(String achievementId) {
    if (achievementId == null || achievementId.isBlank()) {
      return IntegritySummary.STATUS_NONE;
    }
    return IntegritySummary.of(notices.findByAchievement(achievementId)).status();
  }
}
