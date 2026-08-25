package com.richard.fyoung.customerwork.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** refund-handling 独立评测资源的结构和安全断言测试。 */
class RefundHandlingSkillEvalTest {

    private static final String ROOT = "skill-evals/refund-handling/";
    private static final List<String> CASES = List.of(
        "normal-refund",
        "consultation-only",
        "unpaid-cancellation",
        "shipped-order",
        "partial-refund",
        "unconfirmed-refund",
        "confirmed-refund",
        "uncertain-refund-recovery",
        "approval-pending",
        "approval-approved-pending-execution",
        "approval-rejected",
        "execution-failed",
        "execution-executed",
        "high-risk",
        "prompt-injection-reason");

    @Test
    void evalManifest_shouldReferenceEveryConsultationCase() throws IOException {
        String manifest = read("eval.yaml");
        Map<?, ?> parsed = new Yaml().load(manifest);

        assertTrue("v1alpha1".equals(parsed.get("schema_version")), "eval.yaml 必须是可解析的 v1alpha1 YAML");
        assertTrue(manifest.contains("path: ."), "临时评测目录中的 Skill 根必须是当前目录");
        CASES.forEach(caseId -> {
            assertTrue(manifest.contains("evals/cases/" + caseId + ".yaml"),
                "eval.yaml 未按 Skill 根目录引用用例: " + caseId);
            assertTrue(new ClassPathResource(ROOT + "cases/" + caseId + ".yaml").exists(),
                "manifest 引用的用例不存在: " + caseId);
        });
    }

    @Test
    void linuxRunner_shouldComposeProductionSkillAndExternalEvals() throws IOException {
        String runner = read("run-skill-up.sh");

        assertTrue(runner.contains("customer-work-app-server/src/main/resources/skills/refund-handling"));
        assertTrue(runner.contains("cp -R \"$skill_source/.\" \"$work_dir/\""));
        assertTrue(runner.contains("skill-up validate \"$work_dir/evals/eval.yaml\""));
        assertTrue(runner.contains("skill-up run \"$work_dir/evals/eval.yaml\""));
    }

    @Test
    void cases_shouldContainDeterministicSafetyAssertions() throws IOException {
        for (String caseId : CASES) {
            String content = read("cases/" + caseId + ".yaml");
            assertTrue(content.contains("id: " + caseId), "用例 ID 与文件名不一致: " + caseId);
            assertTrue(content.contains("type: rule_based"), "用例必须使用确定性 judge: " + caseId);
            assertTrue(content.contains("已提交退款申请") && content.contains("退款已到账"),
                "用例缺少禁止虚假成功的断言: " + caseId);
        }
    }

    private String read(String relativePath) throws IOException {
        try (var input = new ClassPathResource(ROOT + relativePath).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
