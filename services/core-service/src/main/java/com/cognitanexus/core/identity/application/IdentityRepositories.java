package com.cognitanexus.core.identity.application;

import com.cognitanexus.core.identity.domain.Account;
import com.cognitanexus.core.identity.domain.AccountCredentials;
import com.cognitanexus.core.identity.domain.PortalAuthority;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 身份模块的持久化出站端口。
 *
 * <p>依据：编码规范 §2.2。端口由应用层定义、基础设施实现，装配层通过构造器注入；
 * 禁止把端口放在 infrastructure 后要求领域反向依赖。
 */
public interface IdentityRepositories {

  /** 账号仓储。 */
  interface AccountRepository {

    Optional<Account> findById(String id);

    /** 认证视图：含口令哈希与锁定状态，仅供认证流程使用。 */
    Optional<AccountCredentials> findCredentialsByUsernameNorm(String usernameNorm);

    /**
     * 按账号读取认证视图。
     *
     * <p>改密需要先校验「当前口令」，而改密的调用方只有 {@code ActorContext}（含账号 ID），
     * 没有登录名。用账号 ID 取凭据，避免在应用层把 ID 再换回登录名。
     */
    Optional<AccountCredentials> findCredentialsById(String accountId);

    void insert(Account account, String rawPasswordHash);

    /** 停用账号并提升认证版本，使既有会话立即失效。受影响行数须为 1。 */
    int disable(String accountId);

    Set<String> findRoles(String accountId);

    void grantRole(String accountId, String role);

    /** 撤销角色；返回受影响行数（0 表示该账号本来就没有这个角色）。 */
    int revokeRole(String accountId, String role);

    /** 提升认证版本，使既有会话失效。角色变更后必须调用，否则旧会话仍带着旧角色。 */
    int bumpAuthVersion(String accountId);

    /**
     * 写入新口令并提升认证版本（同一条 UPDATE）。
     *
     * <p>受影响行数为 0 表示账号不存在或已停用，调用方须按冲突处理而不是静默成功。
     */
    int updatePassword(String accountId, String passwordHash, boolean mustChangePassword);

    /**
     * 统计处于 ACTIVE 状态且持有指定角色的账号数。
     *
     * <p>供平台引导判定「是否已存在平台管理员」使用，不作为通用统计接口。
     */
    long countActiveByRole(String role);

    List<Account> listAll(int limit);

    /**
     * 启用账号的 ID 列表，供系统公告广播使用。
     *
     * <p>只返回 ACTIVE：停用账号无法登录，写给它只会造成「还在接收消息」的错觉。
     * {@code limit} 是硬上限，调用方据此判断是否被截断，不能把它当分页用。
     */
    java.util.List<String> listActiveAccountIds(int limit);

    /** 登录成功：清零失败计数、解除锁定并记录最近登录时刻。 */
    void recordLoginSuccess(String accountId);

    /**
     * 登录失败：累加失败次数，达到阈值时按给定秒数锁定。
     *
     * <p>阈值与锁定时长是工程默认值，须与业务确认后调整；这里不宣称已通过安全评审。
     */
    void recordLoginFailure(String accountId, int lockThreshold, int lockSeconds);

    /** 数据库当前时刻。锁定与租约比较一律使用数据库时钟，不依赖各机器本地时间。 */
    Instant now();
  }

  /** 门户授权仓储。 */
  interface PortalAuthorityRepository {

    Optional<PortalAuthority> findActiveByScholar(String scholarId);

    Optional<PortalAuthority> findActiveByAccountAndScholar(String accountId, String scholarId);

    List<PortalAuthority> listActiveByAccount(String accountId);

    Optional<PortalAuthority> findById(String id);

    /**
     * 授予授权。若该门户已存在生效授权，插入被唯一约束忽略并返回 false：竞争认领不转移原授权
     * （AC-IMPL-FR05-03）。
     */
    boolean insertActive(PortalAuthority authority, String grantedByAccountId);

    /** 条件撤销；返回是否成功由 ACTIVE 转为 REVOKED。 */
    boolean revoke(String authorityId, String reason, Instant at);
  }

  /** 会话撤销登记。退出先持久登记撤销，再清 Redis 会话，避免 Redis 恢复旧数据后会话复活。 */
  interface SessionRevocationRepository {

    void register(String sessionIdHash, String accountId, String reason);

    boolean isRevoked(String sessionIdHash);

    int purgeExpired(int retentionDays);
  }
}
