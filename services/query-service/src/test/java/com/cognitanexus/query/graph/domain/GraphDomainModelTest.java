package com.cognitanexus.query.graph.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 图领域模型的不变量：证据、规范顺序、强度、版本水位。 */
class GraphDomainModelTest {

  private static GraphWorkEvidence evidence(String workId) {
    return new GraphWorkEvidence(workId, "Title of " + workId, "REAL");
  }

  @Test
  @DisplayName("合作边两端按规范 ID 升序归一，证据按 workId 去重排序")
  void edgeCanonicalizesEndpointsAndEvidence() {
    GraphCollaborationEdge edge =
        new GraphCollaborationEdge(
            "s-2", "s-1", 2, List.of(evidence("w-2"), evidence("w-1"), evidence("w-2")));

    assertThat(edge.scholarA()).isEqualTo("s-1");
    assertThat(edge.scholarB()).isEqualTo("s-2");
    assertThat(edge.pairKey()).isEqualTo("s-1\u0001s-2");
    assertThat(edge.strength()).isEqualTo(2);
    assertThat(edge.evidence()).extracting(GraphWorkEvidence::workId).containsExactly("w-1", "w-2");
    assertThat(edge.evidenceTruncated()).isFalse();
  }

  @Test
  @DisplayName("没有证据的合作边被拒绝：无法核对的关系不能出现在图里")
  void edgeWithoutEvidenceIsRejected() {
    assertThatThrownBy(() -> new GraphCollaborationEdge("s-1", "s-2", 0, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GraphCollaborationEdge("s-1", "s-2", 1, List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("证据");
  }

  @Test
  @DisplayName("证据截断时强度仍可大于证据条数，并显式标记 truncated")
  void truncatedEvidenceKeepsExactStrength() {
    GraphCollaborationEdge edge =
        new GraphCollaborationEdge("s-1", "s-2", 7, List.of(evidence("w-1"), evidence("w-2")));

    assertThat(edge.strength()).isEqualTo(7);
    assertThat(edge.evidence()).hasSize(2);
    assertThat(edge.evidenceTruncated()).isTrue();
  }

  @Test
  @DisplayName("同一条边的两路来源合并后按 workId 去重，强度不回退")
  void mergeDedupesEvidence() {
    GraphCollaborationEdge materialized =
        new GraphCollaborationEdge("s-1", "s-2", 1, List.of(evidence("w-1")));
    GraphCollaborationEdge bipartite =
        new GraphCollaborationEdge("s-2", "s-1", 2, List.of(evidence("w-1"), evidence("w-2")));

    GraphCollaborationEdge merged = GraphCollaborationEdge.merge(materialized, bipartite);

    assertThat(merged.strength()).isEqualTo(2);
    assertThat(merged.evidence())
        .extracting(GraphWorkEvidence::workId)
        .containsExactly("w-1", "w-2");
  }

  @Test
  @DisplayName("参与关系只接受 AUTO / SELF_CONFIRMED")
  void authorshipOnlyAcceptsQualifiedStates() {
    assertThat(new GraphAuthorship("s-1", "AUTO", 1).relationState()).isEqualTo("AUTO");
    assertThat(new GraphAuthorship("s-1", "SELF_CONFIRMED", null).relationState())
        .isEqualTo("SELF_CONFIRMED");

    for (String state : List.of("PENDING_VERIFY", "DISPUTED", "EXCLUDED", "auto")) {
      assertThatThrownBy(() -> new GraphAuthorship("s-1", state, 1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("非合格参与关系");
    }
  }

  @Test
  @DisplayName("投影水位只前进，重放旧版本不回退")
  void projectionMetaMonotonic() {
    GraphProjectionMeta meta = GraphProjectionMeta.empty("query-public");
    assertThat(meta.projectedVersion()).isZero();
    assertThat(meta.behind(1)).isTrue();
    assertThat(meta.behind(0)).isFalse();

    GraphProjectionMeta advanced = meta.advance(120, 130, "42");
    assertThat(advanced.projectedVersion()).isEqualTo(120);
    assertThat(advanced.catalogRevision()).isEqualTo(130);
    assertThat(advanced.generation()).isEqualTo("42");
    assertThat(advanced.updatedAt()).isAfter(Instant.EPOCH);

    GraphProjectionMeta stale = advanced.advance(100, 100, "41");
    assertThat(stale).isSameAs(advanced);
    assertThat(stale.behind(120)).isFalse();
    assertThat(stale.behind(121)).isTrue();
  }

  @Test
  @DisplayName("筛选条件默认 REAL，ALL 表示不限制，非法值明确失败")
  void filterCriteriaNormalization() {
    assertThat(GraphFilterCriteria.realOnly().dataNature()).isEqualTo("REAL");
    assertThat(GraphFilterCriteria.realOnly().hasWorkLevelFilter()).isFalse();
    assertThat(GraphFilterCriteria.all().dataNature()).isNull();
    assertThat(GraphFilterCriteria.all().dataNatureLabel()).isEqualTo("ALL");
    assertThat(new GraphFilterCriteria("simulated", "t-1", " org-1 ").dataNature())
        .isEqualTo("SIMULATED");
    assertThat(new GraphFilterCriteria("REAL", "t-1", null).hasWorkLevelFilter()).isTrue();
    assertThat(new GraphFilterCriteria("REAL", null, "org-1").hasWorkLevelFilter()).isTrue();
    assertThat(new GraphFilterCriteria("REAL", " ", " ").hasWorkLevelFilter()).isFalse();

    assertThatThrownBy(() -> new GraphFilterCriteria("FAKE", null, null))
        .isInstanceOf(com.cognitanexus.sharedkernel.BusinessException.class);
  }

  @Test
  @DisplayName("路径结果的结构约束：可达必有跳数/节点/证据，不可达不得带路径")
  void pathResultInvariants() {
    GraphCollaborationEdge edge =
        new GraphCollaborationEdge("s-1", "s-2", 1, List.of(evidence("w-1")));

    GraphPathResult reachable =
        new GraphPathResult(true, 1, List.of("s-1", "s-2"), List.of(edge), "ok");
    assertThat(reachable.hopEdges()).hasSize(1);

    assertThatThrownBy(
            () -> new GraphPathResult(true, 2, List.of("s-1", "s-2"), List.of(edge), "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GraphPathResult(false, 0, List.of("s-1"), List.of(), "bad"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(GraphPathResult.unreachable("none").hops()).isNull();
  }
}
