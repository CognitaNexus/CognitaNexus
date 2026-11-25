package com.cognitanexus.query.graph.projection.domain;

import com.cognitanexus.query.graph.domain.GraphAuthorship;
import com.cognitanexus.query.graph.domain.GraphScholarNode;
import com.cognitanexus.query.graph.domain.GraphWorkNode;
import java.util.List;

/**
 * 从 PostgreSQL 读投影组装出来的一份「成果及其图投影所需全部数据」。
 *
 * <p>为什么要把四张表（{@code qry_achievement}、{@code qry_contribution}、
 * {@code qry_achievement_topic}、{@code qry_contribution_org}）在同一次读取里拼成一份对象：
 * ADR-005 §6.2 把「{@code Work.topic_ids} 必须等于 {@code qry_achievement_topic}」和
 * 「{@code Work.organizations} 必须等于 {@code qry_contribution_org}」写成了筛选等价的前提。
 * 若分批读取时四张表的读取时刻不同，一条成果的图节点可能是「新作者 + 旧机构」的混合体，
 * 而带筛选的查询会因此比 PG 少/多出一批成果，响应里没有任何字段能提示这种偏差。
 *
 * <p>{@code eligible} 表达「是否合格公开成果」：只有真实、公开、生效的成果进图；否则同步时
 * 走 {@code retireWork} 留下墓碑并删掉物化合作边。用同一个对象表达两种结局，是为了让调用方
 * 无法「忘了处理不合格成果」——不处理就等于幽灵边。
 *
 * <p>{@code scholars} 只包含有姓名的参与学者：{@code qry_contribution.scholar_name} 允许为空，
 * 而 {@code upsertScholars} 会把姓名写成传入值，用空名覆盖已有姓名会让检索里的作者名永久丢失。
 */
public record GraphProjectedWork(
    String workId,
    String title,
    boolean eligible,
    String dataOrigin,
    long sourceVersion,
    List<String> topicIds,
    List<String> organizations,
    List<GraphAuthorship> authorships,
    List<GraphScholarNode> scholars) {

  public GraphProjectedWork {
    if (workId == null || workId.isBlank()) {
      throw new IllegalArgumentException("workId 不能为空");
    }
    workId = workId.trim();
    title = title == null ? "" : title;
    if (dataOrigin == null || dataOrigin.isBlank()) {
      throw new IllegalArgumentException("dataOrigin 不能为空（REAL/SIMULATED/MIXED）");
    }
    dataOrigin = dataOrigin.trim();
    sourceVersion = Math.max(sourceVersion, 0L);
    topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
    organizations = organizations == null ? List.of() : List.copyOf(organizations);
    authorships = authorships == null ? List.of() : List.copyOf(authorships);
    scholars = scholars == null ? List.of() : List.copyOf(scholars);
  }

  /** 转成图写端口需要的成果节点；不合格成果同样转换，由调用方决定 retire。 */
  public GraphWorkNode toWorkNode() {
    return new GraphWorkNode(
        workId, title, eligible, dataOrigin, sourceVersion, topicIds, organizations);
  }
}
