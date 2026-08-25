package com.richard.fyoung.customerworkapp;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 两个业务 Skill 生产资源包的结构和安全边界测试。 */
class BusinessSkillPackageTest {

    private static final List<Spec> SPECS = List.of(
        new Spec("order-logistics", "queryLogistics(orderId)", "validate-logistics-evidence.py"),
        new Spec("complaint-escalation", "fileComplaint(orderId", "sanitize-complaint-evidence.py"));

    @Test
    void productionPackages_shouldContainEntrypointsReferencesAndDefensiveScripts() throws IOException {
        for (Spec spec : SPECS) {
            String root = "skills/" + spec.id() + "/";
            assertTrue(resource(root + "SKILL.md").exists(), spec.id());
            assertTrue(resource(root + "references/api-contract.md").exists(), spec.id());
            assertTrue(resource(root + "references/" +
                (spec.id().equals("order-logistics") ? "logistics-policy.md" : "complaint-policy.md")).exists(), spec.id());
            assertTrue(resource(root + "scripts/" + spec.script()).exists(), spec.id());
            assertFalse(resource(root + "skill.yaml").exists(), "运行时尚未支持 skill.yaml: " + spec.id());
            assertTrue(read(root + "SKILL.md").contains(spec.requiredTool()), spec.id());
            assertTrue(read(root + "SKILL.md").contains("不直接退款") ||
                read(root + "SKILL.md").contains("不调用订单写操作"), spec.id());
        }
    }

    private ClassPathResource resource(String path) {
        return new ClassPathResource(path);
    }

    private String read(String path) throws IOException {
        try (var input = resource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record Spec(String id, String requiredTool, String script) {
    }
}
