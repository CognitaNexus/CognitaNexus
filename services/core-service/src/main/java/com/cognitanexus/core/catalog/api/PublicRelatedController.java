package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.RelatedAchievementService;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 相关成果的公开读取。
 *
 * <p>单独一个端点而不是塞进成果详情：推荐依赖四张表的联结，耗时与详情页不是一个量级；
 * 详情页首屏不该为了一段「你可能也感兴趣」多等一两百毫秒。前端在详情渲染完成后异步取，
 * 取不到就不显示这一块。
 *
 * <p>公开资格判定在 {@link RelatedAchievementService} 里复用详情与引用网络同一套规则，
 * 本类不做第二份判断。
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicRelatedController {

  private final RelatedAchievementService related;

  public PublicRelatedController(RelatedAchievementService related) {
    this.related = related;
  }

  @GetMapping("/achievements/{achievementId}/related")
  public ResponseEntity<ApiResponse<Map<String, Object>>> related(
      @PathVariable String achievementId, @RequestParam(defaultValue = "8") int limit) {
    return ResponseEntity.ok(
        ApiResponse.ok(related.related(achievementId, limit), TraceIdHolder.current()));
  }
}
