package com.richard.fyoung.customerwork.data.skill;

import com.richard.fyoung.customerwork.tool.backend.RefundHandlingReleaseVersions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * refund-handling 生产包的发布门禁与不可变制品摘要。
 *
 * <p>运行时仍只加载 AgentScope 支持的 Markdown Skill 和 references；本类是校验器，不引入
 * 运行时尚未支持的 {@code skill.yaml} 或脚本清单。发布前通过同一 ClassLoader 检查实际资源，
 * 以防只更新文档、遗漏 Backend/Tool/数据库或把评测资源误发布到生产包。</p>
 */
public final class RefundHandlingReleaseManifest {

    public static final String ROOT = "skills/refund-handling/";
    public static final List<String> REQUIRED_FILES = List.of(
        "SKILL.md",
        "references/refund-policy.md",
        "references/order-state-rules.md",
        "references/tool-contracts.md");
    public static final List<String> REQUIRED_RULE_IDS = List.of(
        "REFUND-001", "REFUND-002", "REFUND-003", "REFUND-004", "REFUND-005",
        "REFUND-006", "REFUND-007");
    public static final List<String> REQUIRED_TOOL_CONTRACTS = List.of(
        "queryOrder(orderId)", "checkRefundEligibility(orderId)",
        "prepareRefund(orderId, reasonCode, reasonText)",
        "submitRefund(orderId, reasonCode, reasonText, confirmationToken)",
        "queryRefundApplication(orderId)", "queryRefundProgress(orderId)");

    private static final Pattern VERSION_PATTERN = Pattern.compile(
        "<!--\\s*version:\\s*(\\d+\\.\\d+\\.\\d+)\\s*-->", Pattern.CASE_INSENSITIVE);

    private RefundHandlingReleaseManifest() {
    }

    /**
     * 检查 classpath 上实际可加载的生产 Skill 包，并计算规范化 SHA-256 摘要。
     *
     * @param loader 提供生产资源的 ClassLoader
     * @return 当前加载资源对应的发布单元和摘要
     * @throws IOException 资源读取失败
     */
    public static Bundle inspect(ClassLoader loader) throws IOException {
        Objects.requireNonNull(loader, "loader");
        StringBuilder canonical = new StringBuilder();
        String skill = null;
        String contracts = null;
        String policy = null;
        for (String path : REQUIRED_FILES) {
            String content = read(loader, ROOT + path);
            canonical.append(path).append('\n').append(normalizeLineEndings(content)).append('\n');
            if ("SKILL.md".equals(path)) {
                skill = content;
            } else if (path.endsWith("refund-policy.md")) {
                policy = content;
            } else if (path.endsWith("tool-contracts.md")) {
                contracts = content;
            }
        }

        String skillVersion = versionOf(skill, "SKILL.md");
        String contractVersion = versionOf(contracts, "references/tool-contracts.md");
        requireEquals(RefundHandlingReleaseVersions.SKILL_VERSION, skillVersion, "Skill 版本");
        requireEquals(RefundHandlingReleaseVersions.TOOL_CONTRACT_VERSION, contractVersion, "Tool 契约版本");
        requireContains(policy, "政策版本：`" + RefundHandlingReleaseVersions.POLICY_VERSION + "`", "政策版本");
        for (String ruleId : REQUIRED_RULE_IDS) {
            requireContains(policy, ruleId, "政策规则 " + ruleId);
        }
        for (String contract : REQUIRED_TOOL_CONTRACTS) {
            requireContains(contracts, contract, "Tool 契约 " + contract);
        }
        requireContains(skill, "transferToHuman", "人工升级入口");
        requireContains(skill, "EXECUTION_FAILED", "执行失败状态");
        if (loader.getResource(ROOT + "skill.yaml") != null
                || loader.getResource(ROOT + "scripts") != null) {
            throw new IllegalStateException("生产 Skill 包包含运行时未支持的 skill.yaml 或 scripts");
        }

        ReleaseTuple versions = new ReleaseTuple(
            RefundHandlingReleaseVersions.SKILL_VERSION,
            RefundHandlingReleaseVersions.POLICY_VERSION,
            RefundHandlingReleaseVersions.TOOL_CONTRACT_VERSION,
            RefundHandlingReleaseVersions.BACKEND_VERSION,
            RefundHandlingReleaseVersions.DATABASE_SCHEMA_VERSION);
        return new Bundle(versions, sha256(canonical.toString()), REQUIRED_FILES);
    }

    /** 生成带发布人和时间的不可变发布记录；调用方应将返回值写入发布审计。 */
    public static ReleaseRecord publishRecord(ClassLoader loader, Instant publishedAt, String publishedBy)
            throws IOException {
        Objects.requireNonNull(publishedAt, "publishedAt");
        if (publishedBy == null || publishedBy.isBlank()) {
            throw new IllegalArgumentException("publishedBy must not be blank");
        }
        Bundle bundle = inspect(loader);
        return new ReleaseRecord(bundle.versions(), bundle.artifactHash(), publishedAt,
            publishedBy.trim());
    }

    /** 兼容校验不把制品哈希当作可忽略字段：同一版本组合也必须对应同一摘要。 */
    public static boolean compatible(ReleaseRecord actual, ReleaseRecord approved) {
        return actual != null && approved != null
            && actual.versions().equals(approved.versions())
            && Objects.equals(actual.artifactHash(), approved.artifactHash());
    }

    private static String read(ClassLoader loader, String path) throws IOException {
        try (InputStream input = loader.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("缺少生产 Skill 资源: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String versionOf(String content, String path) {
        Matcher matcher = VERSION_PATTERN.matcher(content == null ? "" : content);
        if (!matcher.find()) {
            throw new IllegalStateException(path + " 缺少版本标记");
        }
        return matcher.group(1);
    }

    private static void requireContains(String content, String expected, String label) {
        if (content == null || !content.contains(expected)) {
            throw new IllegalStateException(label + "缺失或漂移: " + expected);
        }
    }

    private static void requireEquals(String expected, String actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException(label + "不兼容，expected=" + expected + ", actual=" + actual);
        }
    }

    private static String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record ReleaseTuple(String skillVersion, String policyVersion, String toolContractVersion,
                               String backendVersion, String databaseSchemaVersion) {
        public ReleaseTuple {
            skillVersion = normalize(skillVersion);
            policyVersion = normalize(policyVersion);
            toolContractVersion = normalize(toolContractVersion);
            backendVersion = normalize(backendVersion);
            databaseSchemaVersion = normalize(databaseSchemaVersion);
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record Bundle(ReleaseTuple versions, String artifactHash, List<String> files) {
        public Bundle {
            Objects.requireNonNull(versions, "versions");
            Objects.requireNonNull(artifactHash, "artifactHash");
            files = List.copyOf(files);
        }
    }

    public record ReleaseRecord(ReleaseTuple versions, String artifactHash, Instant publishedAt,
                                String publishedBy) {
        public ReleaseRecord {
            Objects.requireNonNull(versions, "versions");
            Objects.requireNonNull(artifactHash, "artifactHash");
            Objects.requireNonNull(publishedAt, "publishedAt");
            publishedBy = publishedBy == null ? "" : publishedBy.trim();
        }
    }
}
