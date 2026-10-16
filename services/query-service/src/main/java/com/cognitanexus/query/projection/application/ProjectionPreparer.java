package com.cognitanexus.query.projection.application;

import com.cognitanexus.query.corelink.CoreInternalClient;
import com.cognitanexus.query.projection.domain.InboxEventRecord;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 投影应用前的事务外准备。
 *
 * <p>依据：架构 V0.2 §6.1「事务内不访问外部 HTTP、Redis 或文件服务」、§5.4（合并必须留下重定向）。
 *
 * <p>为什么需要这一步：成果合并事件只带标识，被合并者的参与关系已被 core 迁移到幸存者，而事件
 * 载荷里没有幸存者迁移后的完整投影。查询侧若自行拼接，就等于在第二个地方重新实现了一遍核心
 * 合并语义，两边迟早不一致；因此向 core 取权威投影。<b>取回动作必须在事务外</b>，否则一次 core
 * 慢响应会把数据库连接和进度行锁一起占住，其他投递周期全部排队。
 */
@Component
class ProjectionPreparer {

  private final CoreInternalClient core;

  ProjectionPreparer(CoreInternalClient core) {
    this.core = core;
  }

  /** 准备结果。{@code survivorProjection} 为空表示该成果当前不具公开资格，或本事件无需外部数据。 */
  record Prepared(InboxEventRecord event, Optional<Map<String, Object>> survivorProjection) {}

  Prepared prepare(InboxEventRecord event) {
    if (!QueryEventTypes.ACHIEVEMENT_MERGED.equals(event.eventType())) {
      return new Prepared(event, Optional.empty());
    }
    String survivorId = event.stringOf("survivorId");
    if (survivorId == null) {
      // 缺标识时不需要外部调用，交由应用逻辑隔离该事件
      return new Prepared(event, Optional.empty());
    }
    return new Prepared(event, core.exportAchievement(survivorId));
  }
}
