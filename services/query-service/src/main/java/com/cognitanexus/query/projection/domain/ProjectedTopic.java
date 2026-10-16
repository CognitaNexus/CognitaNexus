package com.cognitanexus.query.projection.domain;

/**
 * 领域读投影。
 *
 * <p>依据：架构 V0.2 §9.2。同时保留标识与规范名：领域是多对多且互不排斥的维度，统计与筛选
 * 都按标识聚合（名称可变、标识稳定），而展示需要名称。只留 id 会让展示层回头调 core 解析，
 * 使读路径依赖权威服务。
 */
public record ProjectedTopic(String topicId, String canonicalName) {}
