package com.cognitanexus.core.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cognitanexus.sharedkernel.AccountId;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 角色到能力的映射与校验的行为约束。
 *
 * <p>依据：FR15（认证与授权）、BR-IMP-12（前端隐藏入口不构成授权）、架构 V0.2 §10.2「三层授权」。
 *
 * <p>测试关注两类容易出错的边界：<b>未登录与已登录但无权限必须给出不同错误</b>（否则前端无法
 * 区分「去登录」与「真的没权限」），以及<b>能力集合必须能被逐项枚举断言</b>——用「大概包含」
 * 的断言写测试，等于没测授权。
 */
class CapabilitiesTest {

  private static ActorContext actor(String... roles) {
    return new ActorContext(new AccountId("acc_1"), "tester", "测试账号", Set.of(roles), 1L);
  }

  @Test
  @DisplayName("未登录（actor 为 null）没有任何能力")
  void anonymousHasNothing() {
    assertThat(Capabilities.of(null)).isEmpty();
    assertThat(Capabilities.has(null, RoleNames.CAP_CLAIM_SUBMIT)).isFalse();
    assertThat(Capabilities.has(null, RoleNames.CAP_SOURCE_MANAGE)).isFalse();
  }

  @Test
  @DisplayName("普通用户有提交认领、维护自己门户、自助提交成果三项能力")
  void userCapabilities() {
    assertThat(Capabilities.of(actor(RoleNames.USER)))
        .containsExactlyInAnyOrder(
            RoleNames.CAP_CLAIM_SUBMIT,
            RoleNames.CAP_PORTAL_MAINTAIN,
            RoleNames.CAP_SUBMISSION_SUBMIT);
  }

  @Test
  @DisplayName("普通用户不具备审核、来源管理、账户管理能力——这是水平越权的主要入口")
  void userCannotReviewOrAdminister() {
    ActorContext user = actor(RoleNames.USER);
    assertThat(Capabilities.has(user, RoleNames.CAP_CLAIM_REVIEW)).isFalse();
    assertThat(Capabilities.has(user, RoleNames.CAP_SOURCE_MANAGE)).isFalse();
    assertThat(Capabilities.has(user, RoleNames.CAP_ACCOUNT_MANAGE)).isFalse();
    assertThat(Capabilities.has(user, RoleNames.CAP_GOVERNANCE_MANAGE)).isFalse();
    // 自助提交与审核分开：能提交的人不能自己批自己，否则「审核」就只是一句提示语。
    assertThat(Capabilities.has(user, RoleNames.CAP_SUBMISSION_REVIEW)).isFalse();
  }

  @Test
  @DisplayName("审核者的能力集合逐项固定：多一项就是越权，少一项就是功能不可用")
  void reviewerCapabilities() {
    assertThat(Capabilities.of(actor(RoleNames.REVIEWER)))
        .containsExactlyInAnyOrder(
            RoleNames.CAP_CLAIM_REVIEW,
            RoleNames.CAP_CHANGE_REVIEW,
            RoleNames.CAP_GOVERNANCE_MANAGE,
            RoleNames.CAP_AUDIT_READ,
            RoleNames.CAP_ACHIEVEMENT_CONFIRM,
            RoleNames.CAP_SUBMISSION_REVIEW);
  }

  @Test
  @DisplayName("审核者不代表能管理来源与账户：审核数据与管理系统是两件事")
  void reviewerIsNotAdmin() {
    ActorContext reviewer = actor(RoleNames.REVIEWER);
    assertThat(Capabilities.has(reviewer, RoleNames.CAP_SOURCE_MANAGE)).isFalse();
    assertThat(Capabilities.has(reviewer, RoleNames.CAP_ACCOUNT_MANAGE)).isFalse();
  }

  @Test
  @DisplayName("平台管理员拥有全部十一项能力")
  void adminHasAll() {
    assertThat(Capabilities.of(actor(RoleNames.PLATFORM_ADMIN)))
        .containsExactlyInAnyOrderElementsOf(Capabilities.all());
    // 这条数字断言是刻意的：新增能力时它会失败，逼着人来确认「这个能力该给谁」。
    assertThat(Capabilities.all()).hasSize(11);
  }

  @Test
  @DisplayName("多角色取并集，不取交集：叠加角色只能增加能力")
  void rolesUnion() {
    assertThat(Capabilities.of(actor(RoleNames.USER, RoleNames.REVIEWER)))
        .containsExactlyInAnyOrder(
            RoleNames.CAP_CLAIM_SUBMIT,
            RoleNames.CAP_PORTAL_MAINTAIN,
            RoleNames.CAP_CLAIM_REVIEW,
            RoleNames.CAP_CHANGE_REVIEW,
            RoleNames.CAP_GOVERNANCE_MANAGE,
            RoleNames.CAP_AUDIT_READ,
            RoleNames.CAP_ACHIEVEMENT_CONFIRM,
            RoleNames.CAP_SUBMISSION_SUBMIT,
            RoleNames.CAP_SUBMISSION_REVIEW);
  }

  @Test
  @DisplayName("未知角色不产生任何能力：身份编号未登记时按最小权限处理")
  void unknownRoleGrantsNothing() {
    assertThat(Capabilities.of(actor("SUPERUSER"))).isEmpty();
  }

  @Test
  @DisplayName("require 未登录抛 AUTH_REQUIRED（前端应跳登录，不是提示无权限）")
  void requireWithoutActorAsksLogin() {
    assertThatThrownBy(() -> Capabilities.require(null, RoleNames.CAP_CLAIM_SUBMIT))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.AUTH_REQUIRED));
  }

  @Test
  @DisplayName("require 已登录但无能力抛 ACCESS_DENIED（前端应提示无权限，不是跳登录）")
  void requireWithoutCapabilityDenies() {
    assertThatThrownBy(
            () -> Capabilities.require(actor(RoleNames.USER), RoleNames.CAP_SOURCE_MANAGE))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).errorCode())
                    .isEqualTo(ErrorCode.ACCESS_DENIED));
  }

  @Test
  @DisplayName("require 具备能力时静默通过")
  void requirePassesWhenGranted() {
    Capabilities.require(actor(RoleNames.REVIEWER), RoleNames.CAP_CLAIM_REVIEW);
    Capabilities.require(actor(RoleNames.PLATFORM_ADMIN), RoleNames.CAP_SOURCE_MANAGE);
  }

  @Test
  @DisplayName("能力集合不可变：调用方不得就地增补自己的能力")
  void capabilitySetImmutable() {
    assertThat(Capabilities.of(actor(RoleNames.USER))).isUnmodifiable();
    assertThat(Capabilities.all()).isUnmodifiable();
  }

  @Test
  @DisplayName("all() 覆盖全部能力常量，新增能力时不会漏登记")
  void allCoversEveryDeclaredCapability() {
    assertThat(Capabilities.all())
        .contains(
            RoleNames.CAP_CLAIM_REVIEW,
            RoleNames.CAP_CLAIM_SUBMIT,
            RoleNames.CAP_PORTAL_MAINTAIN,
            RoleNames.CAP_CHANGE_REVIEW,
            RoleNames.CAP_SOURCE_MANAGE,
            RoleNames.CAP_GOVERNANCE_MANAGE,
            RoleNames.CAP_AUDIT_READ,
            RoleNames.CAP_ACCOUNT_MANAGE,
            RoleNames.CAP_ACHIEVEMENT_CONFIRM,
            RoleNames.CAP_SUBMISSION_SUBMIT,
            RoleNames.CAP_SUBMISSION_REVIEW);
  }
}
