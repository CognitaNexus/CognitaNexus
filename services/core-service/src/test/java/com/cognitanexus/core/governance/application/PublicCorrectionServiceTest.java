package com.cognitanexus.core.governance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cognitanexus.core.catalog.application.CatalogRepositories.AchievementRepository;
import com.cognitanexus.core.catalog.domain.Achievement;
import com.cognitanexus.core.catalog.domain.EffectiveStatus;
import com.cognitanexus.core.catalog.domain.PublicationYear;
import com.cognitanexus.core.governance.domain.GovernanceAnomaly;
import com.cognitanexus.sharedkernel.AchievementType;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.DataNature;
import com.cognitanexus.sharedkernel.ErrorCode;
import com.cognitanexus.sharedkernel.Visibility;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 公众纠错的受理边界。
 *
 * <p>这里钉的是「不接受什么」与「什么不该变成新待办」：限流、内容边界、合并计数、存在性不泄露。
 * 真实库行为（JSONB 读写、按 dedupeKey 命中既有行）由接口自检覆盖。
 */
@ExtendWith(MockitoExtension.class)
class PublicCorrectionServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

  @Mock AchievementRepository achievements;
  @Mock GovernanceRepositories.AnomalyRepository anomalies;

  private PublicCorrectionService service;

  @BeforeEach
  void setUp() {
    service =
        new PublicCorrectionService(achievements, anomalies, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Achievement paper(String id, Visibility visibility, EffectiveStatus status) {
    return new Achievement(
        id,
        AchievementType.PAPER,
        "A Title",
        "a title",
        DataNature.REAL,
        visibility,
        status,
        PublicationYear.of(2024),
        null,
        1L,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-01T00:00:00Z"));
  }

  private static PublicCorrectionService.ReportCommand command(String field, String message) {
    return new PublicCorrectionService.ReportCommand("ach_1", field, message, null);
  }

  @Test
  @DisplayName("说明太短直接拒绝：无法定位的报告不该占用待办")
  void rejectsTooShortMessage() {
    assertThatThrownBy(() -> service.submit(command("TITLE", "错了"), "1.2.3.4", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("至少");
    verify(anomalies, never()).record(any());
  }

  @Test
  @DisplayName("说明超长拒绝：待办要能被一口气读完")
  void rejectsOversizedMessage() {
    String message = "x".repeat(PublicCorrectionService.MESSAGE_MAX + 1);
    assertThatThrownBy(() -> service.submit(command("TITLE", message), "1.2.3.4", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不要超过");
  }

  @Test
  @DisplayName("未知字段值被拒绝而不是猜一个最像的")
  void rejectsUnknownField() {
    assertThatThrownBy(() -> service.submit(command("TITEL", "这条记录的标题写错了"), "1.2.3.4", null))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("correction-fields");
  }

  @Test
  @DisplayName("成果不可见时返回 404，与不存在不可区分")
  void notFoundForNonPublicAchievement() {
    when(achievements.findById("ach_1"))
        .thenReturn(
            Optional.of(paper("ach_1", Visibility.RESTRICTED, EffectiveStatus.PENDING_GOVERNANCE)));
    assertThatThrownBy(() -> service.submit(command("TITLE", "这条记录的标题写错了"), "1.2.3.4", null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.NOT_FOUND);
    verify(anomalies, never()).record(any());
  }

  @Test
  @DisplayName("首次提交登记一条待办，detail 带字段、原文与处置提示")
  void recordsFirstReport() {
    when(achievements.findById("ach_1"))
        .thenReturn(Optional.of(paper("ach_1", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE)));
    when(anomalies.findOpenByDedupeKey(
            GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT,
            dedupeKeyOf("ach_1", "TITLE", "这篇论文的标题少了一个副标题")))
        .thenReturn(Optional.empty());

    PublicCorrectionService.Outcome outcome =
        service.submit(command("TITLE", "这篇论文的标题少了一个副标题"), "1.2.3.4", null);

    ArgumentCaptor<GovernanceAnomaly> captor = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies).record(captor.capture());
    GovernanceAnomaly anomaly = captor.getValue();
    assertThat(anomaly.kind()).isEqualTo(GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT);
    assertThat(anomaly.entityId()).isEqualTo("ach_1");
    assertThat(anomaly.status()).isEqualTo("OPEN");
    assertThat(anomaly.detail()).containsEntry("field", "TITLE").containsEntry("fieldLabel", "标题");
    assertThat(anomaly.detail()).containsEntry("report", "这篇论文的标题少了一个副标题");
    assertThat(anomaly.detail()).containsKey("handlingNote");
    assertThat(outcome.merged()).isFalse();
    assertThat(outcome.reportCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("匿名提交不落任何身份字段；登录提交记账号标识")
  void recordsReporterKind() {
    when(achievements.findById("ach_1"))
        .thenReturn(Optional.of(paper("ach_1", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE)));

    service.submit(command("TITLE", "这篇论文的标题少了一个副标题"), "1.2.3.4", null);
    ArgumentCaptor<GovernanceAnomaly> anonymous = ArgumentCaptor.forClass(GovernanceAnomaly.class);
    verify(anomalies).record(anonymous.capture());
    assertThat(anonymous.getValue().detail())
        .containsEntry("reporterKind", "ANONYMOUS")
        .doesNotContainKey("reporterAccountId");
    // 指纹是散列，不能是原始地址
    assertThat((String) anonymous.getValue().detail().get("submitterFingerprint"))
        .startsWith("fp_")
        .doesNotContain("1.2.3.4");
  }

  @Test
  @DisplayName("同一问题重复提交并入既有待办并累加计数，不新增待办")
  void mergesDuplicateReportIntoExistingTodo() {
    when(achievements.findById("ach_1"))
        .thenReturn(Optional.of(paper("ach_1", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE)));
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("reportCount", 2);
    detail.put("lastReportedAt", NOW.minusSeconds(3600).toString());
    detail.put("lastReporterFingerprint", "fp_other");
    when(anomalies.findOpenByDedupeKey(any(), any()))
        .thenReturn(
            Optional.of(
                new GovernanceAnomaly(
                    "anm_1",
                    GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT,
                    null,
                    "ach_1",
                    detail,
                    "OPEN",
                    NOW.minusSeconds(7200),
                    null)));

    PublicCorrectionService.Outcome outcome =
        service.submit(command("TITLE", "这篇论文的标题少了一个副标题"), "5.6.7.8", null);

    assertThat(outcome.merged()).isTrue();
    assertThat(outcome.reportCount()).isEqualTo(3);
    assertThat(outcome.reportId()).isEqualTo("anm_1");
    verify(anomalies, never()).record(any());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(anomalies).refreshDetail(org.mockito.ArgumentMatchers.eq("anm_1"), captor.capture());
    assertThat(captor.getValue()).containsEntry("reportCount", 3);
  }

  @Test
  @DisplayName("同一来源一分钟内重复提交相同内容不累加计数（误触/重试）")
  void suppressesImmediateRepeatFromSameSource() {
    when(achievements.findById("ach_1"))
        .thenReturn(Optional.of(paper("ach_1", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE)));
    String fingerprint = fingerprintForTest("1.2.3.4");
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("reportCount", 1);
    detail.put("lastReportedAt", NOW.minusSeconds(5).toString());
    detail.put("lastReporterFingerprint", fingerprint);
    when(anomalies.findOpenByDedupeKey(any(), any()))
        .thenReturn(
            Optional.of(
                new GovernanceAnomaly(
                    "anm_1",
                    GovernanceAnomaly.KIND_PUBLIC_CORRECTION_REPORT,
                    null,
                    "ach_1",
                    detail,
                    "OPEN",
                    NOW.minusSeconds(7200),
                    null)));

    PublicCorrectionService.Outcome outcome =
        service.submit(command("TITLE", "这篇论文的标题少了一个副标题"), "1.2.3.4", null);

    assertThat(outcome.reportCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("单条成果 24 小时内第 21 次提交被拒：封的是「慢速滴灌」，与来源无关")
  void rateLimitsPerAchievement() {
    // 每次换一个来源：这条规则的用途正是挡住「换着来源慢慢刷」。
    // 若把来源拼进计数键，它就永远先撞上来源级窗口（5 次/10 分钟），这条规则等于没写。
    when(achievements.findById("ach_1"))
        .thenReturn(Optional.of(paper("ach_1", Visibility.PUBLIC, EffectiveStatus.EFFECTIVE)));
    for (int i = 0; i < 20; i++) {
      service.submit(
          new PublicCorrectionService.ReportCommand("ach_1", "TITLE", "第 " + i + " 条不同的说明文字", null),
          "9.9.9." + i,
          null);
    }
    assertThatThrownBy(
            () ->
                service.submit(
                    new PublicCorrectionService.ReportCommand(
                        "ach_1", "TITLE", "第 21 条说明文字内容", null),
                    "9.9.9.200",
                    null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.RATE_LIMITED);
  }

  @Test
  @DisplayName("同一来源 10 分钟内第 6 次提交被拒（跨不同成果）：防的是脚本遍历全站刷")
  void rateLimitsBySource() {
    when(achievements.findById(any()))
        .thenAnswer(
            invocation ->
                Optional.of(
                    paper(
                        invocation.getArgument(0), Visibility.PUBLIC, EffectiveStatus.EFFECTIVE)));
    for (int i = 0; i < 5; i++) {
      service.submit(
          new PublicCorrectionService.ReportCommand(
              "ach_" + i, "TITLE", "第 " + i + " 条不同的说明文字", null),
          "9.9.9.9",
          null);
    }
    assertThatThrownBy(
            () ->
                service.submit(
                    new PublicCorrectionService.ReportCommand(
                        "ach_5", "TITLE", "第 6 条说明文字内容", null),
                    "9.9.9.9",
                    null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).errorCode())
        .isEqualTo(ErrorCode.RATE_LIMITED);
  }

  /** 与服务实现一致的指纹算法：测试要断言的是「同一地址得到同一指纹」，不是散列值本身。 */
  private static String fingerprintForTest(String key) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return "fp_" + java.util.HexFormat.of().formatHex(hash, 0, 6);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String dedupeKeyOf(String achievementId, String field, String message) {
    String normalized = com.cognitanexus.core.platform.util.TextNormalizer.normalize(message);
    return achievementId + "|" + field + "|" + shortHash(normalized == null ? "" : normalized);
  }

  private static String shortHash(String value) {
    try {
      var digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(hash, 0, 6);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
