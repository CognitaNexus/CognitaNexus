package com.cognitanexus.core.taxonomy.domain;

/**
 * 一条领域归属的推断结果。
 *
 * <p>四个字段都是必需的：领域名与得分是结论，置信度是结论的强度，证据是结论的来由。缺少证据的
 * 推断无法复查，也就无法在日后证明它当初为什么这么判。
 *
 * @param fieldName 受控词表中的领域规范名
 * @param score 加权得分；权重 5 的判别词命中题名计 5 分，仅在摘要命中按摘要系数折减
 * @param confidence 0..1 的置信度，同时受证据强度与首位优势影响
 * @param evidence 命中明细，形如「命中 2 项：title:neural network=5.0」
 */
public record TopicClassification(
    String fieldName, double score, double confidence, String evidence) {}
