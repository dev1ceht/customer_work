package com.richard.fyoung.customerworkapp;

import com.richard.fyoung.customerwork.core.agent.CustomerServiceAgentFactory;
import com.richard.fyoung.customerwork.core.agent.AgentGovernanceAssembler;
import com.richard.fyoung.customerwork.core.service.CustomerServiceService;
import com.richard.fyoung.customerwork.core.service.SessionStateManager;
import com.richard.fyoung.customerwork.core.support.TenantResolver;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;
import com.richard.fyoung.customerwork.infra.config.CustomerWorkProperties;
import com.richard.fyoung.customerwork.tool.DefaultActiveGroupsToolkit;
import com.richard.fyoung.customerwork.tool.backend.OrderQueryResult;
import com.richard.fyoung.customerwork.tool.backend.RefundApplicationStatus;
import com.richard.fyoung.customerwork.tool.backend.RefundEligibilityResult;
import com.richard.fyoung.customerwork.tool.backend.RefundPreparationResult;
import com.richard.fyoung.customerwork.tool.backend.RefundProgressResult;
import com.richard.fyoung.customerwork.tool.backend.RefundSubmissionResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 从 CustomerServiceService 入口验证退款咨询的真实 ReAct Tool 调用顺序。 */
class RefundConsultationBehaviorTest {

    private static final String CONSULTATION_REPLY =
        "当前符合首期仅退款条件，可退金额 199.00 元；本次仅完成咨询，实际申请需转人工。";
    private static final String APPLICATION_REPLY =
        "已准备退款摘要：全部可退金额 199.00 元，履约将被冻结，申请需要人工审核。请明确回复确认后再创建申请。";
    private static final String CONFIRMED_REPLY =
        "已创建退款申请 RF-123，当前状态为 PENDING_REVIEW，等待人工审核；不会自动打款或宣称已经到账。";
    private static final String UNKNOWN_REPLY =
        "退款申请结果暂时无法确认，系统未自动重复提交，已转人工核对；不会宣称申请已受理或已经到账。";
    private static final String PARTIAL_REPLY =
        "首期不支持部分退款金额计算或提交，已转人工核对可退余额。";
    private static final String HIGH_RISK_REPLY =
        "该退款请求命中高风险规则，当前只能转人工复核；不会自动提交、审批或打款。";
    private static final String PENDING_PROGRESS_REPLY =
        "退款申请 RF-123 当前为 PENDING_REVIEW，正在等待人工审核；尚未执行或到账。";
    private static final String APPROVED_PROGRESS_REPLY =
        "退款申请 RF-123 已通过人工审核，当前为 APPROVED_PENDING_EXECUTION，等待执行，尚未到账。";
    private static final String REJECTED_PROGRESS_REPLY =
        "退款申请 RF-123 已被人工拒绝，履约冻结已释放；如需继续处理请联系人工申诉，系统不会自动重新提交。";

    @Test
    void structuredRefundResults_shouldExecuteThroughAgentScopeToolkit() {
        List<String> calls = new ArrayList<>();
        Toolkit toolkit = new DefaultActiveGroupsToolkit();
        toolkit.registerTool(new ObservableRefundTools(calls));
        ToolUseBlock use = toolUse(
            "direct-order-call", "queryOrder", Map.of("orderId", "20260613003"));

        var result = toolkit.callTool(ToolCallParam.builder()
            .toolUseBlock(use)
            .input(use.getInput())
            .build()).block(Duration.ofSeconds(5));

        String output = ((TextBlock) result.getOutput().get(0)).getText();
        assertEquals(List.of("queryOrder"), calls, () -> "tool state=" + result.getState()
            + ", output=" + output);
        assertTrue(output.contains("\"queryStatus\":\"FOUND\""), output);
    }

    @Test
    void consultation_shouldQueryOrderThenEligibility_withoutSubmittingRefund() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-consultation-session";
        CustomerServiceService service = service(sessionId, calls);

        String reply = service.chat(sessionId, "订单 20260613003 能退款吗？")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryOrder", "checkRefundEligibility"), calls);
        assertEquals(CONSULTATION_REPLY, reply);
    }

    @Test
    void unconfirmedApplication_shouldPrepareSummary_andWaitForConfirmation() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-application-intent-session";
        CustomerServiceService service = service(sessionId, calls, unconfirmedRefundModel());

        String reply = service.chat(sessionId, "请现在就退款订单 20260613003")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryOrder", "checkRefundEligibility", "prepareRefund"), calls,
            "未明确确认时只能展示摘要，不能调用 submitRefund");
        assertEquals(APPLICATION_REPLY, reply);
    }

    @Test
    void confirmedApplication_shouldPrepareThenCreatePendingReview() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-confirmed-application-session";
        CustomerServiceService service = service(sessionId, calls, confirmedRefundModel());

        String reply = service.chat(sessionId, "我确认申请退款订单 20260613003")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryOrder", "checkRefundEligibility", "prepareRefund", "submitRefund"), calls);
        assertEquals(CONFIRMED_REPLY, reply);
        assertTrue(reply.contains("PENDING_REVIEW") && reply.contains("RF-123"));
        assertFalse(reply.contains("退款已到账") || reply.contains("人工已批准"));
    }

    @Test
    void pendingReviewProgress_shouldOnlyExplainWaitingForHumanApproval() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-pending-progress-session";
        RefundProgressResult progress = progress(RefundApplicationStatus.PENDING_REVIEW,
            ApprovalStatus.PENDING, ExecutionStatus.NOT_APPLICABLE);
        CustomerServiceService service = service(sessionId, calls,
            progressModel(PENDING_PROGRESS_REPLY), new ObservableRefundTools(calls, progress));

        String reply = service.chat(sessionId, "订单 20260613003 的退款到哪一步了？")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryRefundProgress"), calls);
        assertTrue(reply.contains("PENDING_REVIEW") && reply.contains("人工审核"), reply);
        assertFalse(reply.contains("退款已到账") || reply.contains("已为您退款"));
    }

    @Test
    void approvedProgress_shouldNotClaimFundsWereExecuted() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-approved-progress-session";
        RefundProgressResult progress = progress(RefundApplicationStatus.APPROVED_PENDING_EXECUTION,
            ApprovalStatus.APPROVED, ExecutionStatus.NOT_APPLICABLE);
        CustomerServiceService service = service(sessionId, calls,
            progressModel(APPROVED_PROGRESS_REPLY), new ObservableRefundTools(calls, progress));

        String reply = service.chat(sessionId, "人工审核通过了吗，退款到账了吗？")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryRefundProgress"), calls);
        assertTrue(reply.contains("人工审核") && reply.contains("等待执行"), reply);
        assertFalse(reply.contains("退款已到账") || reply.contains("已为您退款"));
    }

    @Test
    void rejectedProgress_shouldRecommendAppeal_withoutSubmittingAgain() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-rejected-progress-session";
        RefundProgressResult progress = progress(RefundApplicationStatus.REJECTED,
            ApprovalStatus.DENIED, ExecutionStatus.NOT_APPLICABLE);
        CustomerServiceService service = service(sessionId, calls,
            progressModel(REJECTED_PROGRESS_REPLY), new ObservableRefundTools(calls, progress));

        String reply = service.chat(sessionId, "退款申请被拒绝了吗？可以再自动提交一次吗？")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryRefundProgress"), calls,
            "拒绝结果只允许查询并引导人工，不得自动再次写入");
        assertTrue(reply.contains("拒绝") && reply.contains("人工申诉"), reply);
        assertFalse(reply.contains("退款已到账") || reply.contains("已为您退款"));
    }

    @Test
    void uncertainSubmission_shouldQueryBeforeAnyRetry_andStopWhenStillUnknown() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-uncertain-result-session";
        CustomerServiceService service = service(sessionId, calls, uncertainRefundModel(),
            new ObservableRefundTools(calls, true));

        String reply = service.chat(sessionId, "确认申请退款订单 20260613003")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryOrder", "checkRefundEligibility", "prepareRefund",
            "submitRefund", "queryRefundApplication"), calls,
            "写入结果未知时必须先查询，持续未知不得自动再次写入");
        assertEquals(UNKNOWN_REPLY, reply);
        assertFalse(reply.contains("已提交退款申请") || reply.contains("退款已到账")
            || reply.contains("已为您退款"));
    }

    @Test
    void partialRefundIntent_shouldTransferToHuman_withoutCalculatingOrSubmittingAmount() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "partial-refund-intent-session";
        CustomerServiceService service = service(sessionId, calls);

        String reply = service.chat(sessionId, "订单 20260613003 只退其中 50 元")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("transferToHuman"), calls,
            "首期不设计部分退款，不应计算或提交用户指定金额");
        assertEquals(PARTIAL_REPLY, reply);
    }

    @Test
    void highRiskEligibility_shouldEscalateWithoutSubmittingRefund() throws IOException {
        List<String> calls = new ArrayList<>();
        String sessionId = "refund-high-risk-session";
        CustomerServiceService service = service(sessionId, calls, highRiskRefundModel(),
            new HighRiskRefundTools(calls));

        String reply = service.chat(sessionId, "请尽快处理订单 20260613007 的退款")
            .block(Duration.ofSeconds(10));

        assertEquals(List.of("queryOrder", "checkRefundEligibility", "transferToHuman"), calls,
            "高风险资格只能升级人工，不能继续准备或提交退款");
        assertEquals(HIGH_RISK_REPLY, reply);
        assertFalse(reply.contains("已提交退款申请") || reply.contains("退款已到账"));
    }

    private CustomerServiceService service(String sessionId, List<String> calls)
            throws IOException {
        return service(sessionId, calls, intentAwareRefundModel());
    }

    private CustomerServiceService service(String sessionId, List<String> calls, Model model)
            throws IOException {
        return service(sessionId, calls, model, new ObservableRefundTools(calls));
    }

    private CustomerServiceService service(String sessionId, List<String> calls, Model model,
                                           ObservableRefundTools tools)
            throws IOException {
        Toolkit toolkit = new DefaultActiveGroupsToolkit();
        toolkit.registerTool(tools);
        ReActAgent.Builder builder = ReActAgent.builder()
            .name("refund-consultation-test")
            .sysPrompt(readSkill())
            .model(model)
            .toolkit(toolkit)
            .maxIters(8);
        new AgentGovernanceAssembler(new CustomerWorkProperties(), mock(TenantResolver.class), null, null)
            .applyTo(builder);
        ReActAgent agent = builder.build();
        CustomerServiceAgentFactory factory = mock(CustomerServiceAgentFactory.class);
        when(factory.createAgent(sessionId)).thenReturn(agent);
        when(factory.contextFor(sessionId)).thenReturn(
            RuntimeContext.builder().userId("U-demo-2").sessionId(sessionId).build());
        return new CustomerServiceService(factory, mock(SessionStateManager.class));
    }

    private Model unconfirmedRefundModel() {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                             List<ToolSchema> tools,
                                             GenerateOptions options) {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    return toolCall("order-call", "queryOrder", Map.of("orderId", "20260613003"));
                }
                if (call == 1) {
                    return toolCall("eligibility-call", "checkRefundEligibility",
                        Map.of("orderId", "20260613003"));
                }
                if (call == 2) {
                    return toolCall("prepare-call", "prepareRefund", Map.of(
                        "orderId", "20260613003",
                        "reasonCode", "CHANGED_MIND",
                        "reasonText", "暂时不需要"));
                }
                return text(APPLICATION_REPLY);
            }

            @Override
            public String getModelName() {
                return "refund-unconfirmed-stub";
            }
        };
    }

    private Model confirmedRefundModel() {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                             List<ToolSchema> tools,
                                             GenerateOptions options) {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    return toolCall("order-call", "queryOrder", Map.of("orderId", "20260613003"));
                }
                if (call == 1) {
                    return toolCall("eligibility-call", "checkRefundEligibility",
                        Map.of("orderId", "20260613003"));
                }
                if (call == 2) {
                    return toolCall("prepare-call", "prepareRefund", Map.of(
                        "orderId", "20260613003",
                        "reasonCode", "CHANGED_MIND",
                        "reasonText", "暂时不需要"));
                }
                if (call == 3) {
                    return toolCall("submit-call", "submitRefund", Map.of(
                        "orderId", "20260613003",
                        "reasonCode", "CHANGED_MIND",
                        "reasonText", "暂时不需要",
                        "confirmationToken", "opaque-server-token"));
                }
                return text(CONFIRMED_REPLY);
            }

            @Override
            public String getModelName() {
                return "refund-confirmed-stub";
            }
        };
    }

    private Model uncertainRefundModel() {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                             List<ToolSchema> tools,
                                             GenerateOptions options) {
                int call = calls.getAndIncrement();
                if (call == 0) {
                    return toolCall("order-call", "queryOrder", Map.of("orderId", "20260613003"));
                }
                if (call == 1) {
                    return toolCall("eligibility-call", "checkRefundEligibility",
                        Map.of("orderId", "20260613003"));
                }
                if (call == 2) {
                    return toolCall("prepare-call", "prepareRefund", Map.of(
                        "orderId", "20260613003",
                        "reasonCode", "CHANGED_MIND",
                        "reasonText", "暂时不需要"));
                }
                if (call == 3) {
                    return toolCall("submit-call", "submitRefund", Map.of(
                        "orderId", "20260613003",
                        "reasonCode", "CHANGED_MIND",
                        "reasonText", "暂时不需要",
                        "confirmationToken", "opaque-server-token"));
                }
                if (call == 4) {
                    return toolCall("query-application-call", "queryRefundApplication",
                        Map.of("orderId", "20260613003"));
                }
                return text(UNKNOWN_REPLY);
            }

            @Override
            public String getModelName() {
                return "refund-uncertain-result-stub";
            }
        };
    }

    private Model highRiskRefundModel() {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                             List<ToolSchema> tools,
                                             GenerateOptions options) {
                if (calls.getAndIncrement() == 0) {
                    return toolCall("order-call", "queryOrder", Map.of("orderId", "20260613007"));
                }
                if (calls.get() == 2) {
                    return toolCall("eligibility-call", "checkRefundEligibility",
                        Map.of("orderId", "20260613007"));
                }
                if (calls.get() == 3) {
                    return toolCall("high-risk-handoff", "transferToHuman",
                        Map.of("reason", "高风险退款需要人工复核"));
                }
                return text(HIGH_RISK_REPLY);
            }

            @Override
            public String getModelName() {
                return "refund-high-risk-stub";
            }
        };
    }

    private Model progressModel(String reply) {
        AtomicInteger calls = new AtomicInteger();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                             List<ToolSchema> tools,
                                             GenerateOptions options) {
                if (calls.getAndIncrement() == 0) {
                    return toolCall("progress-call", "queryRefundProgress",
                        Map.of("orderId", "20260613003"));
                }
                return text(reply);
            }

            @Override
            public String getModelName() {
                return "refund-progress-stub";
            }
        };
    }

    private Model intentAwareRefundModel() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RefundIntent> intent = new AtomicReference<>();
        return new Model() {
            @Override
            public Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                             List<ToolSchema> tools,
                                             GenerateOptions options) {
                RefundIntent current = intent.updateAndGet(existing -> existing == null
                    ? detectIntent(messages)
                    : existing);
                int call = calls.getAndIncrement();
                if (current == RefundIntent.PARTIAL) {
                    if (call == 0) {
                        return toolCall("partial-handoff", "transferToHuman",
                            Map.of("reason", "partial refund requires human review"));
                    }
                    return text(PARTIAL_REPLY);
                }
                if (call == 0) {
                    return toolCall("order-call", "queryOrder", Map.of("orderId", "20260613003"));
                }
                if (call == 1) {
                    return toolCall("eligibility-call", "checkRefundEligibility",
                        Map.of("orderId", "20260613003"));
                }
                if (current == RefundIntent.APPLICATION && call == 2) {
                    return toolCall("application-handoff", "transferToHuman",
                        Map.of("reason", "refund application write path is not available"));
                }
                return text(current == RefundIntent.APPLICATION ? APPLICATION_REPLY : CONSULTATION_REPLY);
            }

            @Override
            public String getModelName() {
                return "refund-consultation-stub";
            }
        };
    }

    private RefundIntent detectIntent(List<io.agentscope.core.message.Msg> messages) {
        String content = messages.stream()
            .filter(message -> message.getRole() == MsgRole.USER)
            .map(io.agentscope.core.message.Msg::getTextContent)
            .collect(Collectors.joining("\n"));
        if (content.contains("只退其中") || content.contains("部分退款")) {
            return RefundIntent.PARTIAL;
        }
        if (content.contains("现在就退款") || content.contains("提交申请")) {
            return RefundIntent.APPLICATION;
        }
        return RefundIntent.CONSULTATION;
    }

    private Flux<ChatResponse> text(String value) {
        ContentBlock text = TextBlock.builder().text(value).build();
        return Flux.just(response(List.of(text), "stop"));
    }

    private Flux<ChatResponse> toolCall(String id, String name, Map<String, Object> input) {
        return Flux.just(response(List.of(toolUse(id, name, input)), "tool_calls"));
    }

    private ToolUseBlock toolUse(String id, String name, Map<String, Object> input) {
        String content = input.entrySet().stream()
            .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
            .collect(Collectors.joining(",", "{", "}"));
        return new ToolUseBlock(id, name, input, content, Map.of());
    }

    private RefundProgressResult progress(RefundApplicationStatus applicationStatus,
                                          ApprovalStatus approvalStatus,
                                          ExecutionStatus executionStatus) {
        return new RefundProgressResult(RefundProgressResult.QueryStatus.FOUND, "20260613003",
            "RF-123", applicationStatus, approvalStatus, executionStatus,
            "refund-policy-2026-01", List.of("REFUND-001", "REFUND-002"), "结构化进度");
    }

    private ChatResponse response(List<? extends ContentBlock> content, String finishReason) {
        return ChatResponse.builder()
            .id(UUID.randomUUID().toString())
            .content(List.copyOf(content))
            .usage(new ChatUsage(1, 1, 0.0))
            .finishReason(finishReason)
            .build();
    }

    private String readSkill() throws IOException {
        try (var input = new ClassPathResource("skills/refund-handling/SKILL.md").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static class ObservableRefundTools {
        protected final List<String> calls;
        private final boolean queryRemainsUnknown;
        private final RefundProgressResult refundProgress;

        ObservableRefundTools(List<String> calls) {
            this(calls, false, null);
        }

        ObservableRefundTools(List<String> calls, boolean queryRemainsUnknown) {
            this(calls, queryRemainsUnknown, null);
        }

        ObservableRefundTools(List<String> calls, RefundProgressResult refundProgress) {
            this(calls, false, refundProgress);
        }

        private ObservableRefundTools(List<String> calls, boolean queryRemainsUnknown,
                                      RefundProgressResult refundProgress) {
            this.calls = calls;
            this.queryRemainsUnknown = queryRemainsUnknown;
            this.refundProgress = refundProgress;
        }

        @Tool(description = "查询结构化订单事实")
        public Mono<OrderQueryResult> queryOrder(
                @ToolParam(name = "orderId", description = "订单号") String orderId) {
            calls.add("queryOrder");
            return Mono.just(new OrderQueryResult(OrderQueryResult.QueryStatus.FOUND, orderId,
                OrderQueryResult.PaymentStatus.PAID, OrderQueryResult.FulfillmentStatus.NOT_SHIPPED,
                "运动防汗蓝牙耳机", new BigDecimal("199.00"), "2026-06-13", List.of()));
        }

        @Tool(description = "由 Backend 计算退款资格")
        public Mono<RefundEligibilityResult> checkRefundEligibility(
                @ToolParam(name = "orderId", description = "订单号") String orderId) {
            calls.add("checkRefundEligibility");
            return Mono.just(new RefundEligibilityResult(RefundEligibilityResult.Decision.ELIGIBLE,
                List.of("ORDER_PAID", "NOT_SHIPPED"), new BigDecimal("199.00"),
                RefundEligibilityResult.RiskLevel.NORMAL, "refund-policy-2026-01",
                List.of("REFUND-001", "REFUND-002")));
        }

        @Tool(description = "准备退款摘要并签发确认令牌")
        public Mono<RefundPreparationResult> prepareRefund(
                @ToolParam(name = "orderId", description = "订单号") String orderId,
                @ToolParam(name = "reasonCode", description = "标准退款原因码") String reasonCode,
                @ToolParam(name = "reasonText", description = "退款原因说明") String reasonText) {
            calls.add("prepareRefund");
            return Mono.just(new RefundPreparationResult(
                RefundPreparationResult.Status.READY, orderId, "运动防汗蓝牙耳机",
                new BigDecimal("199.00"), reasonCode, RefundEligibilityResult.RiskLevel.NORMAL,
                "refund-policy-2026-01", List.of("ORDER_PAID", "NOT_SHIPPED"),
                List.of("REFUND-001", "REFUND-002"), "opaque-server-token", 1780000000000L,
                true, true));
        }

        @Tool(description = "用户明确确认后创建待人工审核退款申请")
        public Mono<RefundSubmissionResult> submitRefund(
                @ToolParam(name = "orderId", description = "订单号") String orderId,
                @ToolParam(name = "reasonCode", description = "标准退款原因码") String reasonCode,
                @ToolParam(name = "reasonText", description = "退款原因说明") String reasonText,
                @ToolParam(name = "confirmationToken", description = "确认令牌") String confirmationToken) {
            calls.add("submitRefund");
            if (queryRemainsUnknown) {
                return Mono.just(new RefundSubmissionResult(
                    RefundSubmissionResult.Status.UNKNOWN, orderId, null, null, null, null,
                    RefundEligibilityResult.RiskLevel.NORMAL, "refund-policy-2026-01",
                    List.of("REFUND_WRITE_RESULT_UNKNOWN"), List.of(),
                    "退款申请结果暂时无法确认，未自动重复提交，已转人工处理",
                    "REFUND:FULL:default:20260613003"));
            }
            return Mono.just(new RefundSubmissionResult(
                RefundSubmissionResult.Status.CREATED, orderId, "RF-123", "AP-123",
                RefundApplicationStatus.PENDING_REVIEW, new BigDecimal("199.00"),
                RefundEligibilityResult.RiskLevel.NORMAL, "refund-policy-2026-01",
                List.of("ORDER_PAID", "NOT_SHIPPED"), List.of("REFUND-001", "REFUND-002"),
                "已创建待人工审核申请", "REFUND:FULL:default:20260613003"));
        }

        @Tool(description = "查询提交结果；结果未知时必须先调用")
        public Mono<RefundSubmissionResult> queryRefundApplication(
                @ToolParam(name = "orderId", description = "订单号") String orderId) {
            calls.add("queryRefundApplication");
            if (queryRemainsUnknown) {
                return Mono.just(new RefundSubmissionResult(
                    RefundSubmissionResult.Status.UNKNOWN, orderId, null, null, null, null,
                    RefundEligibilityResult.RiskLevel.NORMAL, "refund-policy-2026-01",
                    List.of("REFUND_APPLICATION_RESULT_UNKNOWN"), List.of(),
                    "退款申请结果仍无法确认，已转人工处理",
                    "REFUND:FULL:default:20260613003"));
            }
            return Mono.just(new RefundSubmissionResult(
                RefundSubmissionResult.Status.NOT_FOUND, orderId, null, null, null, null,
                RefundEligibilityResult.RiskLevel.NORMAL, "refund-policy-2026-01",
                List.of("REFUND_APPLICATION_NOT_FOUND"), List.of(),
                "未查询到活跃退款申请", "REFUND:FULL:default:20260613003"));
        }

        @Tool(description = "查询结构化退款进度")
        public Mono<RefundProgressResult> queryRefundProgress(
                @ToolParam(name = "orderId", description = "订单号") String orderId) {
            calls.add("queryRefundProgress");
            if (refundProgress != null) {
                return Mono.just(refundProgress);
            }
            return Mono.just(new RefundProgressResult(RefundProgressResult.QueryStatus.NOT_FOUND,
                orderId, null, null, null, null, null, List.of(), "未查询到退款申请"));
        }

        @Tool(description = "转人工处理退款边界")
        public Mono<String> transferToHuman(
                @ToolParam(name = "reason", description = "转人工原因") String reason) {
            calls.add("transferToHuman");
            return Mono.just("transferred: " + reason);
        }
    }

    private static final class HighRiskRefundTools extends ObservableRefundTools {
        private HighRiskRefundTools(List<String> calls) {
            super(calls);
        }

        @Override
        @Tool(description = "查询结构化订单事实")
        public Mono<OrderQueryResult> queryOrder(
                @ToolParam(name = "orderId", description = "订单号") String orderId) {
            calls.add("queryOrder");
            return Mono.just(new OrderQueryResult(OrderQueryResult.QueryStatus.FOUND, orderId,
                OrderQueryResult.PaymentStatus.PAID, OrderQueryResult.FulfillmentStatus.NOT_SHIPPED,
                "高价值订单", new BigDecimal("1299.00"), "2026-06-13", List.of()));
        }

        @Override
        @Tool(description = "由 Backend 计算退款资格")
        public Mono<RefundEligibilityResult> checkRefundEligibility(
                @ToolParam(name = "orderId", description = "订单号") String orderId) {
            super.calls.add("checkRefundEligibility");
            return Mono.just(new RefundEligibilityResult(
                RefundEligibilityResult.Decision.HUMAN_REVIEW,
                List.of("REFUND_HIGH_RISK_AMOUNT"), null,
                RefundEligibilityResult.RiskLevel.HIGH, "refund-policy-2026-01",
                List.of("REFUND-006")));
        }

        @Override
        @Tool(description = "高风险退款转人工复核")
        public Mono<String> transferToHuman(
                @ToolParam(name = "reason", description = "转人工原因") String reason) {
            calls.add("transferToHuman");
            return Mono.just("transferred: high-risk-refund-review");
        }
    }

    private enum RefundIntent {
        CONSULTATION,
        APPLICATION,
        PARTIAL
    }
}
