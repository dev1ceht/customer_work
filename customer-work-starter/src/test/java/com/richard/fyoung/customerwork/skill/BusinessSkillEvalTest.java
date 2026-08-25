package com.richard.fyoung.customerwork.skill;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 订单物流与投诉升级 Skill 评测资源的结构和安全断言。 */
class BusinessSkillEvalTest {

    private static final List<Spec> SPECS = List.of(
        new Spec("order-logistics", List.of("normal-status", "stale-trace", "unknown-order", "signature-dispute")),
        new Spec("complaint-escalation", List.of("normal-complaint", "high-risk-complaint", "privacy-boundary")));

    @Test
    void evalManifests_shouldReferenceAllDeterministicCases() throws IOException {
        for (Spec spec : SPECS) {
            String root = "skill-evals/" + spec.id() + "/";
            String manifest = read(root + "eval.yaml");
            Map<?, ?> parsed = new Yaml().load(manifest);
            assertTrue("v1alpha1".equals(parsed.get("schema_version")), spec.id());
            assertTrue(manifest.contains("type: none"), spec.id());
            for (String caseId : spec.cases()) {
                assertTrue(manifest.contains("evals/cases/" + caseId + ".yaml"), caseId);
                String content = read(root + "cases/" + caseId + ".yaml");
                assertTrue(content.contains("id: " + caseId), caseId);
                assertTrue(content.contains("type: rule_based"), caseId);
                assertTrue(content.contains("not:"), caseId);
            }
        }
    }

    private String read(String path) throws IOException {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Spec(String id, List<String> cases) {
    }
}
