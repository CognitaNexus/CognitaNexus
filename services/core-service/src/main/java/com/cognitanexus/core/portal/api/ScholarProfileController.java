package com.cognitanexus.core.portal.api;

import com.cognitanexus.core.catalog.application.ScholarProfileService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import com.cognitanexus.sharedkernel.BusinessException;
import com.cognitanexus.sharedkernel.ErrorCode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 门户资料自助维护。
 *
 * <p>路径挂在 {@code /api/v1/account/scholars/{scholarId}} 下，而不是公开的
 * {@code /api/v1/public/scholars/{scholarId}}：同一份数据有两个面向——访客读公开视图，
 * 认领人读可编辑视图（含未公开置顶的可用性与上限）。把写操作挂到公开路径上，
 * 会让「这个 URL 能不能匿名访问」变成一个需要逐条记住的例外。
 *
 * <p>所有动作都要求对象级授权，校验在服务层（{@code requireMaintainer}），控制器不重复判断。
 */
@RestController
@RequestMapping("/api/v1/account/scholars/{scholarId}")
public class ScholarProfileController {

  private final ScholarProfileService profiles;

  public ScholarProfileController(ScholarProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/profile")
  public ResponseEntity<ApiResponse<Map<String, Object>>> profile(@PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            profiles.editableProfile(ActorContextHolder.currentOrNull(), scholarId),
            TraceIdHolder.current()));
  }

  /**
   * 修改简介与主页链接。
   *
   * <p>字段缺省表示「不改」，空串表示「清空」——两者合并成一种，前端就无法只改简介而不碰主页。
   */
  @PutMapping("/profile")
  public ResponseEntity<ApiResponse<Map<String, Object>>> updateProfile(
      @PathVariable String scholarId, @RequestBody ProfileUpdate body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            profiles.updateProfile(
                ActorContextHolder.currentOrNull(),
                scholarId,
                body.researchSummary(),
                body.homepageUrl()),
            TraceIdHolder.current()));
  }

  /** 整体替换代表作置顶；传空数组表示清空。 */
  @PutMapping("/featured")
  public ResponseEntity<ApiResponse<Map<String, Object>>> updateFeatured(
      @PathVariable String scholarId, @RequestBody FeaturedUpdate body) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            profiles.updateFeatured(
                ActorContextHolder.currentOrNull(), scholarId, body.achievementIds()),
            TraceIdHolder.current()));
  }

  /** 上传头像。只接受 PNG/JPEG，单文件不超过 2 MiB（上限在存储层，换入口也绕不过）。 */
  @PostMapping("/avatar")
  public ResponseEntity<ApiResponse<Map<String, Object>>> uploadAvatar(
      @PathVariable String scholarId, @RequestPart("file") MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw BusinessException.invalidArgument("请选择要上传的图片文件");
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException ex) {
      throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "图片读取失败");
    }
    return ResponseEntity.ok(
        ApiResponse.ok(
            profiles.uploadAvatar(
                ActorContextHolder.currentOrNull(),
                scholarId,
                file.getOriginalFilename(),
                file.getContentType(),
                content),
            TraceIdHolder.current()));
  }

  @DeleteMapping("/avatar")
  public ResponseEntity<ApiResponse<Map<String, Object>>> removeAvatar(
      @PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            profiles.removeAvatar(ActorContextHolder.currentOrNull(), scholarId),
            TraceIdHolder.current()));
  }

  /** 请求体。两个字段都可缺省；缺省不等于清空。 */
  public record ProfileUpdate(String researchSummary, String homepageUrl) {}

  /** 置顶请求体。按数组顺序决定名次。 */
  public record FeaturedUpdate(List<String> achievementIds) {}
}
