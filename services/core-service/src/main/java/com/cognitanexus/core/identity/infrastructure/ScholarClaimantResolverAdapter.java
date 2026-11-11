package com.cognitanexus.core.identity.infrastructure;

import com.cognitanexus.core.notification.application.NotificationRepositories;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 「这批学者分别被谁认领」的解析实现（ADR-003 §3：合并与撤权通知按账号去重后投递）。
 *
 * <p>放在 identity 模块而不是 notification：认领授权的数据与语义都在这里，notification 只声明
 * 它需要什么能力（端口倒置）。
 */
@Component
public class ScholarClaimantResolverAdapter
    implements NotificationRepositories.ScholarClaimantResolver {

  private final PortalAuthorityMapper authorities;

  public ScholarClaimantResolverAdapter(PortalAuthorityMapper authorities) {
    this.authorities = authorities;
  }

  @Override
  public List<String> resolveActiveAccountIds(Collection<String> scholarIds) {
    if (scholarIds == null || scholarIds.isEmpty()) {
      return List.of();
    }
    return authorities.listActiveAccountIdsByScholars(scholarIds);
  }
}
