package com.cognitanexus.core.portal.application;

import com.cognitanexus.core.platform.audit.AuditRecorder;
import com.cognitanexus.core.platform.security.ActorContext;
import com.cognitanexus.core.platform.security.Capabilities;
import com.cognitanexus.core.platform.security.RoleNames;
import com.cognitanexus.core.portal.domain.Submission;
import com.cognitanexus.sharedkernel.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量导入成果（BibTeX / RIS）。
 *
 * <p>依据：需求基线「学者自主提交成果」的补录场景。设计上有三个决定：
 *
 * <ol>
 *   <li><b>逐条调用 {@link SubmissionService#submit}，不另写一条插入路径。</b>批量导入如果自己
 *       建 Submission，就会绕过查重、幂等、审计与状态机——而这些正是「提交件不是成果」这条
 *       边界的实现处。代价是 N 条 N 个事务，可接受：一次最多 200 条。
 *   <li><b>本类不加 {@code @Transactional}。</b>加了之后内层 submit 会加入同一个事务，一条失败
 *       整批回滚——用户为了 1 条坏数据必须重来 199 条。这里的语义是「能进的都进，坏的单独报」。
 *   <li><b>dryRun 只解析、不写库。</b>用户上传 .bib 之后最想知道的是「它认出了几条、标题对不对」，
 *       确认预览再写库，比先写进去再让他撤回 200 条友好得多。
 * </ol>
 */
@Service
public class SubmissionImportService {

  private static final Logger log = LoggerFactory.getLogger(SubmissionImportService.class);

  /** 单次上传文本上限 2 MiB。.bib 纯文本，200 条大约 200 KiB，2 MiB 已经非常宽松。 */
  private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;

  private final SubmissionService submissions;
  private final AuditRecorder audit;

  /**
   * 只为「整批审计」开的小事务。
   *
   * <p>本类刻意不加 {@code @Transactional}（逐条独立提交），但审计器是
   * {@code Propagation.MANDATORY}——它必须在业务事务里写，否则审计行可能与业务动作不同步。
   * 因此批末那一条审计用一个独立小事务包起来：不是把整批合回一个事务，而是给这条审计
   * 单独一个事务边界。写成自调用 {@code @Transactional} 方法不行，Spring 代理不拦自调用。
   */
  private final TransactionTemplate auditTx;

  public SubmissionImportService(
      SubmissionService submissions, AuditRecorder audit, PlatformTransactionManager txManager) {
    this.submissions = submissions;
    this.audit = audit;
    this.auditTx = new TransactionTemplate(txManager);
  }

  /** 单条导入结果。status 取值：CREATED / DUPLICATE_SUSPECTED / SKIPPED / FAILED。 */
  public record ImportItem(
      int index,
      String citationKey,
      String title,
      String status,
      String submissionId,
      String duplicateKind,
      String duplicateNote,
      List<String> warnings,
      String error) {}

  /** 批量导入结果。skippedNotes 是解析阶段被丢弃的记录的原因，与 items 分开：它们没有条目。 */
  public record ImportResult(
      String format,
      int parsed,
      int created,
      int skipped,
      int failed,
      boolean dryRun,
      List<ImportItem> items,
      List<String> skippedNotes,
      String note) {}

  /**
   * 导入一批文献。
   *
   * @param format AUTO / BIBTEX / RIS；AUTO 按内容特征判断
   * @param content 文件全文
   * @param scholarId 可选：把这些成果挂到某个本人管理的学者档案下
   * @param dryRun true 只解析预览，不写库
   */
  public ImportResult importBatch(
      ActorContext actor, String format, String content, String scholarId, boolean dryRun) {
    Capabilities.require(actor, RoleNames.CAP_SUBMISSION_SUBMIT);
    if (content == null || content.isBlank()) {
      throw BusinessException.invalidArgument("请提供 BibTeX 或 RIS 文本");
    }
    if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
      throw BusinessException.invalidArgument("文件超过 2 MiB，请拆分后再导入");
    }

    BibliographyParser.Format hint = parseFormat(format);
    BibliographyParser.ParseResult parsed = BibliographyParser.parse(content, hint);
    if (parsed.entries().isEmpty()) {
      return new ImportResult(
          parsed.format().name(),
          0,
          0,
          0,
          0,
          dryRun,
          List.of(),
          parsed.skipped(),
          "没有识别到任何条目。请确认文件是 BibTeX（以 @article{ 开头）或 RIS（以 TY  - 开头）格式。");
    }

    List<ImportItem> items = new ArrayList<>(parsed.entries().size());
    int index = 0;
    int created = 0;
    int skipped = 0;
    int failed = 0;
    for (BibliographyParser.Entry entry : parsed.entries()) {
      index++;
      if (!entry.supported()) {
        skipped++;
        items.add(
            new ImportItem(
                index,
                entry.citationKey(),
                entry.title(),
                "SKIPPED",
                null,
                null,
                null,
                entry.warnings(),
                "该类型暂不支持自助提交"));
        continue;
      }
      if (dryRun) {
        items.add(
            new ImportItem(
                index,
                entry.citationKey(),
                entry.title(),
                "PREVIEW",
                null,
                null,
                null,
                entry.warnings(),
                null));
        continue;
      }
      try {
        Submission submission =
            submissions.submit(
                actor,
                new SubmissionService.NewSubmission(
                    "PAPER",
                    entry.title(),
                    entry.doi(),
                    entry.venue(),
                    entry.year(),
                    entry.abstractText(),
                    entry.authorsRaw(),
                    scholarId),
                null);
        created++;
        boolean suspected = submission.duplicateSuspected();
        items.add(
            new ImportItem(
                index,
                entry.citationKey(),
                entry.title(),
                suspected ? "DUPLICATE_SUSPECTED" : "CREATED",
                submission.id(),
                submission.duplicateKind() == null ? null : submission.duplicateKind().name(),
                submission.duplicateNote(),
                entry.warnings(),
                null));
      } catch (BusinessException ex) {
        // 业务异常是「这条数据本身不合规」（题名空、年份越界、学者已合并等），
        // 记进结果让用户自己改，不中断整批。
        failed++;
        items.add(
            new ImportItem(
                index,
                entry.citationKey(),
                entry.title(),
                "FAILED",
                null,
                null,
                null,
                entry.warnings(),
                ex.getMessage()));
      } catch (RuntimeException ex) {
        // 非业务异常（数据库约束、连接中断）不吞：继续处理只会把同一个错误重复 200 次，
        // 而且用户看到 200 条失败也不知道该改什么。记一条日志后整体抛出。
        log.error("批量导入第 {} 条时发生非业务异常，中止", index, ex);
        throw ex;
      }
    }

    if (!dryRun) {
      // 审计条目先在 lambda 外构造：计数是可变局部变量，直接捕获会编译失败；
      // 先在事务外把条目拼好，事务里只做一次写入。
      AuditRecorder.AuditEntry entry =
          AuditRecorder.AuditEntry.of(
              actor,
              "SUBMISSION_IMPORTED",
              "SUBMISSION_BATCH",
              null,
              null,
              Map.of(
                  "format",
                  parsed.format().name(),
                  "parsed",
                  String.valueOf(parsed.entries().size()),
                  "created",
                  String.valueOf(created),
                  "skipped",
                  String.valueOf(skipped),
                  "failed",
                  String.valueOf(failed)),
              "批量导入成果（" + parsed.format().name() + "）");
      auditTx.executeWithoutResult(status -> audit.record(entry));
    }

    Map<String, Object> note = new LinkedHashMap<>();
    note.put(
        "text", dryRun ? "预览不含查重结果；确认导入后会逐条给出查重提示。" : "逐条独立入库：失败的条目不影响已成功的；查重命中只提示，由审核判断是否重复。");
    return new ImportResult(
        parsed.format().name(),
        parsed.entries().size(),
        created,
        skipped,
        failed,
        dryRun,
        items,
        parsed.skipped(),
        String.valueOf(note.get("text")));
  }

  private static BibliographyParser.Format parseFormat(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    if (upper.equals("AUTO")) {
      return null;
    }
    if (upper.equals("BIBTEX") || upper.equals("BIB")) {
      return BibliographyParser.Format.BIBTEX;
    }
    if (upper.equals("RIS")) {
      return BibliographyParser.Format.RIS;
    }
    throw BusinessException.invalidArgument("不支持的格式：" + raw + "（可选 AUTO / BIBTEX / RIS）");
  }
}
