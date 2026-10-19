package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.core.portal.application.SubmissionImportService;
import com.cognitanexus.core.portal.application.SubmissionService;
import com.cognitanexus.core.portal.domain.Submission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的成果提交。
 *
 * <p>路径在 {@code /api/v1/account/submissions} 下：这些是提交人自己的记录，不是公开资源。
 * 审核视角完全不同（要看别人的、要给结论），因此另有一组 {@code /api/v1/admin/core/submissions}
 * ——两组入口共用同一个服务，授权校验在服务层，控制器不各写一份判断。
 */
@RestController
@RequestMapping("/api/v1/account/submissions")
public class SubmissionController {

  private final SubmissionService submissions;
  private final SubmissionImportService imports;

  public SubmissionController(SubmissionService submissions, SubmissionImportService imports) {
    this.submissions = submissions;
    this.imports = imports;
  }

  /**
   * 提交一条成果。
   *
   * <p>{@code Idempotency-Key} 可选：带着它重复提交会返回首次那条（网络重试不会建出两条）；
   * 不带就按新提交处理——作者改完题名再交一次，本来就可能是一条不同的记录。
   */
  @PostMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> submit(
      @RequestBody SubmissionService.NewSubmission body,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    Submission created =
        submissions.submit(ActorContextHolder.currentOrNull(), body, idempotencyKey);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.ok(submissions.view(created), TraceIdHolder.current()));
  }

  @GetMapping
  public ResponseEntity<ApiResponse<Map<String, Object>>> mine(
      @RequestParam(required = false, defaultValue = "20") int limit) {
    List<Submission> rows = submissions.listMine(ActorContextHolder.currentOrNull(), limit);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", submissions.views(rows));
    payload.put("note", "提交件在审核通过前不是成果：不进入公开目录、不产生参与关系，也不会出现在检索结果里");
    return ResponseEntity.ok(ApiResponse.ok(payload, TraceIdHolder.current()));
  }

  /**
   * 从 BibTeX / RIS 文本批量导入。
   *
   * <p>接受文本而不是文件：前端用 FileReader 读出全文再发过来，服务端不需要处理 multipart
   * 与字符集猜测两件事，也不会把用户文件落到磁盘上。.bib 是纯文本，2 MiB 的上限足够 200 条。
   *
   * <p>{@code dryRun=true} 时只解析预览、不写库，响应里每条 status 为 PREVIEW。
   */
  @PostMapping("/import")
  public ResponseEntity<ApiResponse<Map<String, Object>>> importBatch(
      @RequestBody ImportRequest body) {
    SubmissionImportService.ImportResult result =
        imports.importBatch(
            ActorContextHolder.currentOrNull(),
            body.format(),
            body.content(),
            body.scholarId(),
            body.dryRun());
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("format", result.format());
    view.put("parsed", result.parsed());
    view.put("created", result.created());
    view.put("skipped", result.skipped());
    view.put("failed", result.failed());
    view.put("dryRun", result.dryRun());
    view.put("items", result.items().stream().map(SubmissionController::importItemView).toList());
    view.put("skippedNotes", result.skippedNotes());
    view.put("note", result.note());
    return ResponseEntity.ok(ApiResponse.ok(view, TraceIdHolder.current()));
  }

  private static Map<String, Object> importItemView(SubmissionImportService.ImportItem item) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("index", item.index());
    view.put("citationKey", item.citationKey());
    view.put("title", item.title());
    view.put("status", item.status());
    // 下面几项在预览与失败行上是 null，non_null 序列化会把字段整个丢掉；前端按可选处理。
    view.put("submissionId", item.submissionId());
    view.put("duplicateKind", item.duplicateKind());
    view.put("duplicateNote", item.duplicateNote());
    view.put("warnings", item.warnings());
    view.put("error", item.error());
    return view;
  }

  @PostMapping("/{submissionId}/withdraw")
  public ResponseEntity<ApiResponse<Map<String, Object>>> withdraw(
      @PathVariable String submissionId) {
    Submission withdrawn = submissions.withdraw(ActorContextHolder.currentOrNull(), submissionId);
    return ResponseEntity.ok(ApiResponse.ok(submissions.view(withdrawn), TraceIdHolder.current()));
  }

  /** 批量导入请求体。format 取 AUTO / BIBTEX / RIS；content 是文件全文。 */
  public record ImportRequest(String format, String content, String scholarId, boolean dryRun) {}
}
