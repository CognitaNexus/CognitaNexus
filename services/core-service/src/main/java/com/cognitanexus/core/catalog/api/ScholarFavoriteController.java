package com.cognitanexus.core.catalog.api;

import com.cognitanexus.core.catalog.application.ScholarFavoriteService;
import com.cognitanexus.core.platform.security.ActorContextHolder;
import com.cognitanexus.core.platform.web.ApiResponse;
import com.cognitanexus.core.platform.web.TraceIdHolder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 学者收藏。
 *
 * <p>账号侧的增删查在 {@code /api/v1/account/favorites}，公开的收藏数单独一个只读端点：
 * 详情页在未登录时也要显示「N 人收藏」，不能让公开页为了一个数字先要求登录。
 */
@RestController
@RequestMapping("/api/v1")
public class ScholarFavoriteController {

  private final ScholarFavoriteService favorites;

  public ScholarFavoriteController(ScholarFavoriteService favorites) {
    this.favorites = favorites;
  }

  @PostMapping("/account/favorites/{scholarId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> favorite(@PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            view(favorites.favorite(ActorContextHolder.currentOrNull(), scholarId)),
            TraceIdHolder.current()));
  }

  @DeleteMapping("/account/favorites/{scholarId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> unfavorite(
      @PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            view(favorites.unfavorite(ActorContextHolder.currentOrNull(), scholarId)),
            TraceIdHolder.current()));
  }

  @GetMapping("/account/favorites")
  public ResponseEntity<ApiResponse<Map<String, Object>>> mine(
      @RequestParam(defaultValue = "50") int limit) {
    List<Map<String, Object>> items = favorites.list(ActorContextHolder.currentOrNull(), limit);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("items", items);
    payload.put("count", items.size());
    payload.put("note", "收藏是个人列表语义：不会因为该学者有新成果而给你发通知。");
    return ResponseEntity.ok(ApiResponse.ok(payload, TraceIdHolder.current()));
  }

  @GetMapping("/account/favorites/{scholarId}")
  public ResponseEntity<ApiResponse<Map<String, Object>>> status(@PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            favorites.status(ActorContextHolder.currentOrNull(), scholarId),
            TraceIdHolder.current()));
  }

  /** 公开收藏数。只给数量，不返回收藏者名单。 */
  @GetMapping("/public/scholars/{scholarId}/favorite-count")
  public ResponseEntity<ApiResponse<Map<String, Object>>> publicCount(
      @PathVariable String scholarId) {
    return ResponseEntity.ok(
        ApiResponse.ok(
            Map.of(
                "scholarId",
                scholarId,
                "favoriteCount",
                favorites.publicCount(scholarId),
                "note",
                "只公开数量，不公开收藏者"),
            TraceIdHolder.current()));
  }

  private static Map<String, Object> view(ScholarFavoriteService.FavoriteResult result) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("favorited", result.favorited());
    view.put("favoriteCount", result.favoriteCount());
    view.put("note", result.note());
    return view;
  }
}
