package com.richard.fyoung.customerworkapp;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import com.richard.fyoung.customerwork.data.skill.RefundHandlingReleaseManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** refund-handling 生产 Skill 包的结构与安全不变量测试。 */
class RefundHandlingSkillPackageTest {

    private static final String ROOT = "skills/refund-handling/";
    private static final String EXPECTED_ARTIFACT_HASH =
        "05d3d110717fa5ad949ab02835b37034b70f108b3d8441a647db7730c5afa96b";

    @Test
    void productionPackage_shouldContainEntrypointAndThreeFocusedReferences() {
        List<String> required = List.of(
            "SKILL.md",
            "references/refund-policy.md",
            "references/order-state-rules.md",
            "references/tool-contracts.md");

        required.forEach(path -> assertTrue(new ClassPathResource(ROOT + path).exists(),
            "缺少生产 Skill 文件: " + path));
        assertFalse(new ClassPathResource(ROOT + "skill.yaml").exists(),
            "运行时尚未支持 skill.yaml，不应放入生产包");
        assertFalse(new ClassPathResource(ROOT + "scripts").exists(),
            "首期不启用 Skill 脚本执行");
    }

    @Test
    void entrypoint_shouldRouteConsultationThroughReadToolsWithoutSubmittingRefund() throws IOException {
        String skill = read("SKILL.md");
        int queryOrder = skill.indexOf("queryOrder(orderId)");
        int eligibility = skill.indexOf("checkRefundEligibility(orderId)");
        int consultationStop = skill.indexOf("只咨询");

        assertTrue(queryOrder >= 0 && eligibility > queryOrder,
            "Skill 必须先查询订单事实，再检查退款资格");
        assertTrue(consultationStop > eligibility,
            "Skill 必须说明只咨询时在资格结果后停止，不能创建申请");
        assertTrue(skill.contains("references/refund-policy.md"));
        assertTrue(skill.contains("references/order-state-rules.md"));
        assertTrue(skill.contains("references/tool-contracts.md"));
    }

    @Test
    void policyAndToolContract_shouldCarryTraceableBackendOwnedDecisionEvidence() throws IOException {
        String policy = read("references/refund-policy.md");
        String contracts = read("references/tool-contracts.md");

        assertTrue(policy.contains("refund-policy-2026-01"));
        assertTrue(policy.contains("REFUND-001") && policy.contains("REFUND-002")
            && policy.contains("REFUND-005") && policy.contains("REFUND-006")
            && policy.contains("REFUND-007"));
        assertTrue(policy.contains("负责人") && policy.contains("生效"));
        assertTrue(contracts.contains("checkRefundEligibility(orderId)"));
        assertTrue(contracts.contains("queryRefundApplication(orderId)"));
        assertTrue(contracts.contains("queryRefundProgress(orderId)"));
        assertTrue(contracts.contains("APPROVED_PENDING_EXECUTION"));
        assertTrue(contracts.contains("REJECTED") && contracts.contains("CONFLICT"));
        assertTrue(contracts.contains("EXECUTED") && contracts.contains("EXECUTION_FAILED")
                && contracts.contains("idempotencyKey"));
        assertTrue(contracts.contains("paymentStatus") && contracts.contains("fulfillmentStatus"));
        assertTrue(contracts.contains("policyVersion") && contracts.contains("ruleIds"));
    }

    @Test
    void releaseGate_shouldInspectTheActualClasspathBundle() throws IOException {
        RefundHandlingReleaseManifest.Bundle bundle = RefundHandlingReleaseManifest
            .inspect(getClass().getClassLoader());

        assertEquals("1.0.0", bundle.versions().skillVersion());
        assertEquals("refund-policy-2026-01", bundle.versions().policyVersion());
        assertEquals("18", bundle.versions().databaseSchemaVersion());
        assertTrue(bundle.artifactHash().matches("[0-9a-f]{64}"), bundle.artifactHash());
        assertEquals(EXPECTED_ARTIFACT_HASH, bundle.artifactHash(),
            "生产 Skill 资源漂移时必须更新发布记录和兼容版本");
        assertEquals(4, bundle.files().size());
    }

    @Test
    void releaseGate_shouldRejectVersionOrArtifactDrift() throws IOException {
        RefundHandlingReleaseManifest.Bundle bundle = RefundHandlingReleaseManifest
            .inspect(getClass().getClassLoader());
        var approved = RefundHandlingReleaseManifest.publishRecord(getClass().getClassLoader(),
            java.time.Instant.parse("2026-08-24T00:00:00Z"), "release-bot");

        assertTrue(RefundHandlingReleaseManifest.compatible(approved,
            new RefundHandlingReleaseManifest.ReleaseRecord(bundle.versions(), bundle.artifactHash(),
                approved.publishedAt(), approved.publishedBy())));
        assertFalse(RefundHandlingReleaseManifest.compatible(approved,
            new RefundHandlingReleaseManifest.ReleaseRecord(
                new RefundHandlingReleaseManifest.ReleaseTuple("1.0.1",
                    bundle.versions().policyVersion(), bundle.versions().toolContractVersion(),
                    bundle.versions().backendVersion(), bundle.versions().databaseSchemaVersion()),
                bundle.artifactHash(), approved.publishedAt(), approved.publishedBy())));
        assertFalse(RefundHandlingReleaseManifest.compatible(approved,
            new RefundHandlingReleaseManifest.ReleaseRecord(bundle.versions(), "0".repeat(64),
                approved.publishedAt(), approved.publishedBy())));
    }

    @Test
    void boundaryInstructions_shouldRouteUnpaidShippedAndPartialRefundSafely() throws IOException {
        String skill = read("SKILL.md");

        assertTrue(skill.contains("paymentStatus=UNPAID") && skill.contains("取消订单流程"),
            "未支付订单必须进入取消流程");
        assertTrue(skill.contains("fulfillmentStatus=SHIPPED") && skill.contains("退货退款或人工"),
            "已发货订单必须进入退货退款或人工流程");
        assertTrue(skill.contains("用户要求部分退款") && skill.contains("转人工或对应售后流程"),
            "首期不设计部分退款，必须明确转人工");
        assertTrue(skill.contains("UNKNOWN") && skill.contains("queryRefundApplication(orderId)"),
            "写入结果未知时必须先查询，不能盲目重试");
        assertTrue(skill.contains("queryRefundProgress(orderId)")
                && skill.contains("APPROVED_PENDING_EXECUTION")
                && skill.contains("REJECTED")
                && skill.contains("EXECUTED")
                && skill.contains("EXECUTION_FAILED"),
            "进度查询必须区分待审、待执行和拒绝状态");
        assertTrue(skill.contains("禁止自动再次提交") && skill.contains("人工申诉"),
            "拒绝后的 Agent 行为必须停止自动重提并引导人工");
    }

    private String read(String relativePath) throws IOException {
        try (var input = new ClassPathResource(ROOT + relativePath).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
