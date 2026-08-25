package com.richard.fyoung.customerworkapp.controller;

import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingForm;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingResult;
import com.richard.fyoung.customerwork.capability.slotfilling.SlotFillingService;
import com.richard.fyoung.customerwork.core.dto.ChatRequest;
import com.richard.fyoung.customerwork.tool.backend.AfterSalesBackend;
import com.richard.fyoung.customerwork.tool.backend.RefundPreparationResult;
import com.richard.fyoung.customerwork.tool.backend.RefundSubmissionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 退款多轮信息收集端点：收齐后只准备退款摘要，必须由用户明确确认令牌后才提交申请。
 * 终端用户 JWT 由统一安全过滤器校验；控制器不接受 userId、tenantId 或金额字段。
 */
@RestController
@RequestMapping("/api/customer/forms/refund")
@Tag(name = "退款信息收集", description = "多轮槽位收集 + 用户确认后创建待人工审核退款申请")
public class RefundFormController {

    private final SlotFillingService slotFillingService;
    private final AfterSalesBackend afterSalesBackend;

    public RefundFormController(SlotFillingService slotFillingService,
                                AfterSalesBackend afterSalesBackend) {
        this.slotFillingService = slotFillingService;
        this.afterSalesBackend = afterSalesBackend;
    }

    @Operation(summary = "提交一轮退款信息", description = "多轮收集订单号/原因，收齐后返回退款摘要和短期确认令牌，不创建申请")
    @PostMapping
    public Mono<Map<String, Object>> collect(@Valid @RequestBody ChatRequest request) {
        String sessionId = StringUtils.hasText(request.sessionId())
            ? request.sessionId() : "refund-" + UUID.randomUUID();
        SlotFillingResult result = slotFillingService.submit(
            sessionId, SlotFillingForm.refundForm(), request.message());

        if (!result.isComplete()) {
            return Mono.just(Map.of(
                "sessionId", sessionId,
                "complete", false,
                "nextPrompt", result.getNextPrompt(),
                "collected", result.getValues()));
        }

        String orderId = result.getValues().get("orderId");
        String reasonText = result.getValues().get("reason");
        return afterSalesBackend.prepareRefund(orderId, "OTHER", reasonText)
            .map(prepared -> preparedResponse(sessionId, result, prepared));
    }

    @Operation(summary = "确认退款摘要并提交申请",
        description = "仅接受订单号、标准原因码、受限原因文本和服务端签发的确认令牌；金额由 Backend 重算")
    @PostMapping("/submit")
    public Mono<RefundSubmissionResult> submit(@Valid @RequestBody RefundSubmitRequest request) {
        return afterSalesBackend.submitRefund(request.orderId(), request.reasonCode(),
            request.reasonText(), request.confirmationToken());
    }

    private Map<String, Object> preparedResponse(String sessionId, SlotFillingResult collected,
                                                  RefundPreparationResult prepared) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", sessionId);
        response.put("complete", true);
        response.put("collected", collected.getValues());
        response.put("preparation", prepared);
        response.put("message", prepared.status() == RefundPreparationResult.Status.READY
            ? "请核对订单、商品和全部可退金额；履约将被冻结，申请需人工审核且不会自动打款。确认后提交 confirmationToken。"
            : "当前退款申请未直接创建，已转人工审核。");
        return response;
    }

    /** 表单提交合同不包含金额、租户或用户身份字段。 */
    public record RefundSubmitRequest(
        @NotBlank String orderId,
        @NotBlank @Size(max = 32) String reasonCode,
        @NotBlank @Size(max = 500) String reasonText,
        @NotBlank @Size(max = 4096) String confirmationToken) {
    }
}
