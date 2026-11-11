package com.cognitanexus.core.taxonomy.api;

import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.core.taxonomy.application.TopicInferenceService;
import com.cognitanexus.core.taxonomy.application.TopicInferenceService.Assignment;
import com.cognitanexus.core.taxonomy.application.TopicInferenceService.BatchReport;
import com.cognitanexus.core.taxonomy.application.TopicInferenceService.Item;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 领域推断管理端。
 *
 * <p>依据：架构 V0.2 §10.1（路由前缀 /api/v1/admin/core/* 由核心服务拥有；能力校验在应用层完成，
 * URL 前缀不构成授权依据）、SRS 5.3。
 *
 * <p>三个端点分别回答三件事：现在分类到什么程度（inference-summary）、哪些判不出（unresolved）、
 * 以及推进一批（infer）。批量推进刻意做成「一次一批」而不是「一键跑完全部」：单批上限 500 且
 * 整批一个事务，调用方按批推进可以看到每批的产出并在必要时停下；没有「跑完全部」这个按钮，
 * 也就不会出现一个跑了十分钟、中途失败后不知道停在哪的不可观测操作。
 */
@RestController
@RequestMapping("/api/v1/admin/core/topics")
public class AdminTaxonomyController {

  private final TopicInferenceService inference;

  public AdminTaxonomyController(TopicInferenceService inference) {
    this.inference = inference;
  }

  /** 领域归属的覆盖率与台账概况。 */
  @GetMapping("/inference-summary")
  public ResponseEntity<ApiResponse<Map<String, Object>>> inferenceSummary() {
    return ResponseEntity.ok(
        ApiResponse.ok(
            inference.summary(ActorContextHolder.currentOrNull()), TraceIdHolder.current()));
  }

  /** 待分类清单。 */
  @GetMapping("/unresolved")
  public ResponseEntity<ApiResponse<List<Map<String, Object>>>> unresolved(
      @RequestParam(required = false) Integer limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            inference.unresolved(ActorContextHolder.currentOrNull(), limit),
            TraceIdHolder.current()));
  }

  /**
   * 推进一批领域推断。
   *
   * <p>dryRun 允许只算不写：先看清当前词表在这一批数据上会判出什么，再决定要不要落库。
   */
  @PostMapping("/infer")
  public ResponseEntity<ApiResponse<Map<String, Object>>> infer(
      @RequestBody(required = false) InferRequest request) {
    InferRequest effective = request == null ? new InferRequest(null, null, null) : request;
    BatchReport report =
        inference.infer(
            ActorContextHolder.currentOrNull(),
            effective.limit(),
            Boolean.TRUE.equals(effective.dryRun()),
            effective.reason());
    return ResponseEntity.ok(ApiResponse.ok(toView(report), TraceIdHolder.current()));
  }

  public record InferRequest(Integer limit, Boolean dryRun, String reason) {}

  private static Map<String, Object> toView(BatchReport report) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("ruleVersion", report.ruleVersion());
    view.put("taxonomy", report.taxonomy());
    view.put("dryRun", report.dryRun());
    view.put("examined", report.examined());
    view.put("assigned", report.assigned());
    view.put("unresolved", report.unresolved());
    view.put("createdRows", report.createdRows());
    view.put("idempotentRows", report.idempotentRows());
    view.put("supersededRows", report.supersededRows());
    view.put("pendingRemaining", report.pendingRemaining());
    view.put("inferredRows", report.inferredRows());
    view.put("achievementsWithoutTopic", report.achievementsWithoutTopic());
    List<Map<String, Object>> samples = new ArrayList<>();
    for (Item item : report.samples()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("achievementId", item.achievementId());
      row.put("title", item.title());
      List<Map<String, Object>> assignments = new ArrayList<>();
      for (Assignment assignment : item.assignments()) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("topicId", assignment.topicId());
        one.put("field", assignment.fieldName());
        one.put("score", assignment.score());
        one.put("confidence", assignment.confidence());
        one.put("evidence", assignment.evidence());
        assignments.add(one);
      }
      row.put("assignments", assignments);
      // 判不出时给出原因：只说「未分类」，治理人员无从判断是文本不足、阈值过高还是词表缺词。
      if (item.unresolvedReason() != null) {
        row.put("unresolvedReason", item.unresolvedReason());
      }
      samples.add(row);
    }
    view.put("samples", samples);
    return view;
  }
}
