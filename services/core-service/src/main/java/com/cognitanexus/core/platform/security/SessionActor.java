package com.cognitanexus.core.platform.security;

/**
 * 会话中保存的登录态快照。
 *
 * <p>只保存身份与认证版本，不保存权限判定结果：权限每次请求按数据库当前状态重新判定
 * （架构 V0.2 §10.2）。
 *
 * @param accountId 账号标识
 * @param authVersion 登录时的认证版本
 */
public record SessionActor(String accountId, long authVersion) {}
