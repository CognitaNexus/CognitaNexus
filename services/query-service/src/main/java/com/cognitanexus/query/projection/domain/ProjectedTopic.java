package com.cognitanexus.query.projection.domain;

/**
 * 领域读投影。
 *
 * <p>依据：架构 V0.2 §9.2。同时保留标识与规范名：领域是多对多且互不排斥的维度，统计与筛选
 * 都按标识聚合（名称可变、标识稳定），而展示需要名称。只留 id 会让展示层回头调 core 解析，
 * 使读路径依赖权威服务。
 *
 * <p>{@code assignmentKind} 不是附带的装饰信息：领域分类会进入检索分面与领域热点排名，读模型若
 * 把来源分类与系统推断抹成同一种东西，界面就只能把它们混在一起排名，读者无从判断某个排名是
 * 元数据事实还是本系统的规则产出。
 *
 * @param topicId 领域标识
 * @param canonicalName 领域规范名，载荷未提供时为 null
 * @param assignmentKind SOURCE=来源元数据给出的分类，INFERRED=权威服务按受控词表推断
 * @param confidence 推断置信度 0..1；来源分类为 null
 */
public record ProjectedTopic(
    String topicId, String canonicalName, String assignmentKind, Double confidence) {}
