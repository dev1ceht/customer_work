package com.richard.fyoung.customerwork.tool.backend;

/**
 * refund-handling 发布单元使用的兼容版本常量。
 *
 * <p>这些值不是用户可控配置；它们与 Skill 规则快照、Tool 契约、Backend 实现和数据库迁移
 * 一起变更，由发布门禁校验兼容组合。</p>
 */
public final class RefundHandlingReleaseVersions {

    public static final String POLICY_VERSION = "refund-policy-2026-01";
    public static final String SKILL_VERSION = "1.0.0";
    public static final String TOOL_CONTRACT_VERSION = "1.0.0";
    public static final String BACKEND_VERSION = "1.0.0";
    public static final String DATABASE_SCHEMA_VERSION = "18";

    private RefundHandlingReleaseVersions() {
    }
}
