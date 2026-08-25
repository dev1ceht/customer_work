package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.safety.security.UserIdentityContext;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 售后后端的默认演示实现与单元测试实现。
 * 退款金额只由服务端订单事实计算；提交只创建待人工审核申请，不直接打款。
 */
public class MockAfterSalesBackend implements AfterSalesBackend {

    private static final Logger log = LoggerFactory.getLogger(MockAfterSalesBackend.class);
    private static final String TYPE_REFUND = "REFUND";
    private static final String DEFAULT_TEST_SECRET = "task-02-development-only-secret";

    private static final Map<String, MockOrder> MOCK_ORDERS = Map.of(
        "20260613001", new MockOrder("已发货", new BigDecimal("299.00"), "演示商品-已发货", true,
            "user-1", "default"),
        "20260613002", new MockOrder("已签收", new BigDecimal("1599.00"), "演示商品-已签收", true,
            "user-1", "default"),
        "20260613003", new MockOrder("待发货", new BigDecimal("199.00"), "演示商品-待发货", false,
            "user-1", "default"),
        "20260613004", new MockOrder("已退款", new BigDecimal("299.00"), "演示商品-已退款", false,
            "user-1", "default"),
        "20260613005", new MockOrder("待支付", new BigDecimal("399.00"), "演示商品-待支付", false,
            "user-1", "default"),
        "20260613006", new MockOrder("待发货", new BigDecimal("499.00"), "演示商品-已有申请", true,
            "user-1", "default"),
        "20260613007", new MockOrder("待发货", new BigDecimal("1299.00"), "演示商品-高风险", false,
            "user-1", "default")
    );

    private final RefundPolicyEvaluator policyEvaluator = new RefundPolicyEvaluator();
    private final RefundConfirmationTokenService tokenService;
    private final PendingApprovalService approvalService;
    private final CustomerWorkTransactionExecutor transactionExecutor;
    private final RefundPaymentGateway paymentGateway;
    private final Map<String, RefundSubmissionResult> applications = new ConcurrentHashMap<>();
    private final Map<String, String> activeFreezes = new ConcurrentHashMap<>();
    private final Map<String, String> executionReferences = new ConcurrentHashMap<>();
    private final Map<String, String> executionFailures = new ConcurrentHashMap<>();

    /** 默认构造仅供 mock 演示和离线测试；生产装配会注入外部配置的令牌密钥。 */
    public MockAfterSalesBackend() {
        this(new RefundConfirmationTokenService(DEFAULT_TEST_SECRET));
    }

    public MockAfterSalesBackend(RefundConfirmationTokenService tokenService) {
        this(tokenService, new PendingApprovalService(), CustomerWorkTransactionExecutor.DIRECT);
    }

    public MockAfterSalesBackend(RefundConfirmationTokenService tokenService,
                                 PendingApprovalService approvalService,
                                 CustomerWorkTransactionExecutor transactionExecutor) {
        this(tokenService, approvalService, transactionExecutor, RefundPaymentGateway.unavailable());
    }

    public MockAfterSalesBackend(RefundConfirmationTokenService tokenService,
                                 PendingApprovalService approvalService,
                                 CustomerWorkTransactionExecutor transactionExecutor,
                                 RefundPaymentGateway paymentGateway) {
        this.tokenService = tokenService;
        this.approvalService = approvalService;
        this.transactionExecutor = transactionExecutor;
        this.paymentGateway = paymentGateway == null ? RefundPaymentGateway.unavailable() : paymentGateway;
    }

    @Override
    public Mono<RefundEligibilityResult> checkRefundEligibility(String orderId) {
        log.info("mock refund eligibility check: orderId={}", orderId);
        return Mono.fromSupplier(() -> {
                MockOrder order = MOCK_ORDERS.get(orderId);
                return order == null ? policyEvaluator.orderNotFound() : evaluate(order);
            })
            .delayElement(Duration.ofMillis(20))
            .onErrorResume(e -> Mono.just(policyEvaluator.unavailable()));
    }

    @Override
    public Mono<RefundPreparationResult> prepareRefund(String orderId, String reasonCode, String reasonText) {
        return Mono.fromSupplier(() -> doPrepareRefund(orderId, reasonCode, reasonText));
    }

    @Override
    public Mono<RefundSubmissionResult> submitRefund(String orderId, String reasonCode,
                                                     String reasonText, String confirmationToken) {
        return Mono.fromSupplier(() -> doSubmitRefund(orderId, reasonCode, reasonText, confirmationToken));
    }

    @Override
    public Mono<RefundSubmissionResult> queryRefundApplication(String orderId) {
        return Mono.fromSupplier(() -> doQueryRefundApplication(orderId));
    }

    @Override
    public Mono<RefundApprovalResult> approveRefund(String approvalId, String operator, String note) {
        return Mono.fromSupplier(() -> doApproveRefund(approvalId, operator, note));
    }

    @Override
    public Mono<RefundApprovalResult> denyRefund(String approvalId, String operator, String note) {
        return Mono.fromSupplier(() -> doDenyRefund(approvalId, operator, note));
    }

    @Override
    public Mono<RefundExecutionResult> executeRefund(String approvalId) {
        return Mono.fromSupplier(() -> doExecuteRefund(approvalId, false));
    }

    @Override
    public Mono<RefundExecutionResult> retryRefundExecution(String approvalId) {
        return Mono.fromSupplier(() -> doExecuteRefund(approvalId, true));
    }

    @Override
    public Mono<RefundProgressResult> queryRefundProgressStatus(String orderId) {
        return Mono.fromSupplier(() -> doQueryRefundProgressStatus(orderId));
    }

    /** 供契约测试核对 mock 是否发生了重复申请。 */
    int activeApplicationCount() {
        return applications.size();
    }

    /** 供契约测试核对退款重试没有重复创建履约冻结。 */
    int activeFreezeCount() {
        return activeFreezes.size();
    }

    /** 供契约测试核对退款重试没有重复创建人工审批单。 */
    int pendingApprovalCount() {
        return approvalService.list().size();
    }

    private RefundPreparationResult doPrepareRefund(String orderId, String reasonCode, String reasonText) {
        RefundReasonValidator.Validation reason = RefundReasonValidator.validate(reasonCode, reasonText);
        if (!reason.valid()) {
            return preparationReview(orderId, reason.errorCode());
        }
        UserPrincipal principal = UserIdentityContext.get();
        if (!hasIdentity(principal)) {
            return preparationReview(orderId, "REFUND_IDENTITY_REQUIRED");
        }
        MockOrder order = MOCK_ORDERS.get(orderId);
        if (order == null) {
            return preparationIneligible(orderId, "ORDER_NOT_FOUND");
        }
        if (!owns(principal, order)) {
            return preparationReview(orderId, "REFUND_ORDER_OWNERSHIP_MISMATCH");
        }
        RefundEligibilityResult eligibility = evaluate(order);
        if (eligibility.decision() != RefundEligibilityResult.Decision.ELIGIBLE) {
            return preparationFromEligibility(orderId, order, eligibility);
        }
        if (RefundRiskEvaluator.reasonRequiresHumanReview(reason.text())) {
            return preparationHighRisk(orderId, order);
        }
        RefundSubmissionResult existingApplication = applications.get(orderId);
        if (existingApplication != null) {
            return preparationReview(orderId,
                existingApplication.applicationStatus() == RefundApplicationStatus.REJECTED
                    ? "REFUND_APPLICATION_REJECTED" : "REFUND_APPLICATION_EXISTS");
        }
        try {
            String token = tokenService.issue(principal, orderId, eligibility.refundableAmount(),
                eligibility.policyVersion());
            return new RefundPreparationResult(
                RefundPreparationResult.Status.READY, orderId, order.productName(),
                eligibility.refundableAmount(), reason.code().name(), eligibility.riskLevel(),
                eligibility.policyVersion(), eligibility.reasonCodes(), eligibility.ruleIds(), token,
                tokenService.expiresAtMs(), true, true);
        } catch (RuntimeException e) {
            log.error("mock refund preparation failed, code={}, orderId={}",
                "REFUND-PREPARE-TOKEN-FAIL", orderId, e);
            return preparationReview(orderId, "REFUND_CONFIRMATION_UNAVAILABLE");
        }
    }

    private RefundSubmissionResult doSubmitRefund(String orderId, String reasonCode,
                                                   String reasonText, String confirmationToken) {
        RefundReasonValidator.Validation reason = RefundReasonValidator.validate(reasonCode, reasonText);
        if (!reason.valid()) {
            return submissionReview(orderId, reason.errorCode());
        }
        UserPrincipal principal = UserIdentityContext.get();
        if (!hasIdentity(principal)) {
            return submissionReview(orderId, "REFUND_IDENTITY_REQUIRED");
        }
        MockOrder order = MOCK_ORDERS.get(orderId);
        if (order == null) {
            return submissionReview(orderId, "ORDER_NOT_FOUND");
        }
        if (!owns(principal, order)) {
            return submissionReview(orderId, "REFUND_ORDER_OWNERSHIP_MISMATCH");
        }
        String idempotencyKey = RefundIdempotencyKey.fullRefund(principal.tenantId(), orderId);
        RefundSubmissionResult existing = applications.get(orderId);
        if (existing != null) {
            if (existing.applicationStatus() == RefundApplicationStatus.REJECTED) {
                return submissionReview(orderId, "REFUND_APPLICATION_REJECTED");
            }
            return existingResult(existing, idempotencyKey);
        }
        RefundEligibilityResult eligibility = evaluate(order);
        if (eligibility.decision() != RefundEligibilityResult.Decision.ELIGIBLE) {
            return submissionFromEligibility(orderId, eligibility);
        }
        if (RefundRiskEvaluator.reasonRequiresHumanReview(reason.text())) {
            return submissionHighRisk(orderId);
        }
        RefundConfirmationTokenService.Verification verification = tokenService.verify(
            confirmationToken, principal, orderId, eligibility.refundableAmount(), eligibility.policyVersion());
        if (!verification.valid()) {
            return submissionReview(orderId, verification.errorCode());
        }

        synchronized (applications) {
            existing = applications.get(orderId);
            if (existing != null) {
                if (existing.applicationStatus() == RefundApplicationStatus.REJECTED) {
                    return submissionReview(orderId, "REFUND_APPLICATION_REJECTED");
                }
                return existingResult(existing, idempotencyKey);
            }
            try {
                return transactionExecutor.execute(() -> {
                    String refundNo = "RF-" + java.util.UUID.randomUUID();
                    String sessionId = RefundInvocationContext.get();
                    if (sessionId == null || sessionId.isBlank()) {
                        sessionId = "refund-backend";
                    }
                    var approval = approvalService.submit(ApprovalType.REFUND, sessionId,
                        principal.userId(), orderId, eligibility.refundableAmount().toPlainString(),
                        reason.code().name() + ":" + reason.text());
                    activeFreezes.put(orderId, refundNo);
                    RefundSubmissionResult created = new RefundSubmissionResult(
                        RefundSubmissionResult.Status.CREATED, orderId, refundNo, approval.getId(),
                        RefundApplicationStatus.PENDING_REVIEW, eligibility.refundableAmount(),
                        eligibility.riskLevel(), eligibility.policyVersion(), eligibility.reasonCodes(),
                        eligibility.ruleIds(), "退款申请已创建，履约已冻结，等待人工审核；不会自动打款",
                        idempotencyKey);
                    applications.put(orderId, created);
                    return created;
                });
            } catch (RuntimeException e) {
                if (RefundWriteUncertainty.isUncertain(e)) {
                    return submissionUnknown(orderId, idempotencyKey, "REFUND_WRITE_RESULT_UNKNOWN");
                }
                throw e;
            }
        }
    }

    private RefundSubmissionResult doQueryRefundApplication(String orderId) {
        UserPrincipal principal = UserIdentityContext.get();
        if (!hasIdentity(principal)) {
            return submissionReview(orderId, "REFUND_IDENTITY_REQUIRED");
        }
        MockOrder order = MOCK_ORDERS.get(orderId);
        if (order == null) {
            return submissionReview(orderId, "ORDER_NOT_FOUND");
        }
        if (!owns(principal, order)) {
            return submissionReview(orderId, "REFUND_ORDER_OWNERSHIP_MISMATCH");
        }
        String idempotencyKey = RefundIdempotencyKey.fullRefund(principal.tenantId(), orderId);
        RefundSubmissionResult existing = applications.get(orderId);
        return existing == null
            ? submissionNotFound(orderId, idempotencyKey)
            : existingResult(existing, idempotencyKey);
    }

    private RefundApprovalResult doApproveRefund(String approvalId, String operator, String note) {
        synchronized (applications) {
            RefundSubmissionResult current = findApplicationByApproval(approvalId);
            if (current == null) {
                return approvalNotFound(approvalId, "未查询到退款申请");
            }
            if (current.applicationStatus() != RefundApplicationStatus.PENDING_REVIEW) {
                return approvalConflict(current, approvalId, operator, "退款申请已完成审批，不能重复批准");
            }
            try {
                ApprovalRequest approval = approvalService.approveDecisionOnly(approvalId, operator, note);
                RefundSubmissionResult updated = withApplicationStatus(current,
                    RefundApplicationStatus.APPROVED_PENDING_EXECUTION,
                    "退款申请已通过人工审核，等待执行；尚未到账");
                applications.put(current.orderId(), updated);
                return approvalResult(RefundApprovalResult.Status.APPROVED, approval, updated,
                    "退款申请已通过人工审核，等待退款执行；不会自动宣称已到账");
            } catch (IllegalStateException e) {
                return approvalConflict(current, approvalId, operator, "审批已被其他操作员处理");
            }
        }
    }

    private RefundApprovalResult doDenyRefund(String approvalId, String operator, String note) {
        synchronized (applications) {
            RefundSubmissionResult current = findApplicationByApproval(approvalId);
            if (current == null) {
                return approvalNotFound(approvalId, "未查询到退款申请");
            }
            if (current.applicationStatus() != RefundApplicationStatus.PENDING_REVIEW) {
                return approvalConflict(current, approvalId, operator, "退款申请已完成审批，不能重复拒绝");
            }
            try {
                ApprovalRequest approval = approvalService.denyDecisionOnly(approvalId, operator, note);
                RefundSubmissionResult updated = withApplicationStatus(current,
                    RefundApplicationStatus.REJECTED,
                    "退款申请未通过人工审核，可联系人工申诉；不会自动重新提交");
                applications.put(current.orderId(), updated);
                activeFreezes.remove(current.orderId());
                return approvalResult(RefundApprovalResult.Status.REJECTED, approval, updated,
                    "退款申请已被人工拒绝，履约冻结已释放，可联系人工申诉");
            } catch (IllegalStateException e) {
                return approvalConflict(current, approvalId, operator, "审批已被其他操作员处理");
            }
        }
    }

    private RefundExecutionResult doExecuteRefund(String approvalId, boolean retry) {
        synchronized (applications) {
            RefundSubmissionResult current = findApplicationByApproval(approvalId);
            ApprovalRequest approval = approvalService.find(approvalId).orElse(null);
            if (current == null || approval == null) {
                return executionNotFound(approvalId, "未查询到退款执行申请");
            }
            if (approval.getStatus() != com.richard.fyoung.customerwork.capability.approval.ApprovalStatus.APPROVED) {
                return executionConflict(current, approval, "只有人工批准的退款申请可以执行");
            }
            if (current.applicationStatus() == RefundApplicationStatus.EXECUTED) {
                return executionFromCurrent(current, approval, RefundExecutionResult.Status.EXECUTED,
                    "退款已由支付渠道明确受理，不会重复执行");
            }
            if (!retry && current.applicationStatus() != RefundApplicationStatus.APPROVED_PENDING_EXECUTION) {
                return executionConflict(current, approval, "退款申请尚未处于可执行状态");
            }
            if (retry && current.applicationStatus() != RefundApplicationStatus.APPROVED_PENDING_EXECUTION
                && current.applicationStatus() != RefundApplicationStatus.EXECUTION_FAILED) {
                return executionConflict(current, approval, "退款申请当前状态不允许恢复执行");
            }
            if (current.applicationStatus() == RefundApplicationStatus.EXECUTION_FAILED
                && "REFUND_EXECUTION_RESULT_UNKNOWN".equals(executionFailures.get(current.orderId()))) {
                RefundPaymentGateway.RefundPaymentResult query = queryPayment(current.idempotencyKey());
                if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED) {
                    return reconcileAccepted(current, approval, query);
                }
                if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN) {
                    return executionFromCurrent(current, approval, RefundExecutionResult.Status.UNKNOWN,
                        "退款渠道结果仍无法确认，保留履约冻结并转人工核对");
                }
                if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNAVAILABLE) {
                    return executionFromCurrent(current, approval, RefundExecutionResult.Status.UNAVAILABLE,
                        "退款渠道查询不可用，未自动重复执行");
                }
            }
            return startPaymentExecution(current, approval);
        }
    }

    private RefundExecutionResult startPaymentExecution(RefundSubmissionResult application,
                                                         ApprovalRequest approval) {
        String idempotencyKey = application.idempotencyKey();
        java.util.concurrent.atomic.AtomicReference<RefundPaymentGateway.RefundPaymentResult> outcome =
            new java.util.concurrent.atomic.AtomicReference<>();
        boolean completed = approvalService.executeApproved(approval.getId(), 3, idempotencyKey, context -> {
            RefundSubmissionResult current = findApplicationByApproval(approval.getId());
            if (current == null) {
                throw new IllegalStateException("refund application disappeared during execution");
            }
            applications.put(current.orderId(), withApplicationStatus(current,
                RefundApplicationStatus.EXECUTING, "退款执行中，尚未确认到账"));
            RefundPaymentGateway.RefundPaymentResult result;
            try {
                result = paymentGateway.execute(new RefundPaymentGateway.RefundPaymentRequest(
                    current.refundNo(), current.orderId(), current.refundableAmount(), idempotencyKey));
            } catch (Exception e) {
                result = new RefundPaymentGateway.RefundPaymentResult(
                    RefundPaymentGateway.RefundPaymentResult.Status.FAILED, null,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (result == null) {
                result = new RefundPaymentGateway.RefundPaymentResult(
                    RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null,
                    "支付渠道未返回结果");
            }
            outcome.set(result);
            if (result.status() == RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED) {
                applications.put(current.orderId(), withApplicationStatus(current,
                    RefundApplicationStatus.EXECUTED,
                    "退款已提交支付渠道处理，凭证已记录；不承诺银行即时到账"));
                executionReferences.put(current.orderId(), result.reference());
                executionFailures.remove(current.orderId());
                activeFreezes.remove(current.orderId());
                return;
            }
            applications.put(current.orderId(), withApplicationStatus(current,
                RefundApplicationStatus.EXECUTION_FAILED,
                result.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN
                    ? "退款渠道结果无法确认，已保留履约冻结"
                    : "退款执行失败，已保留原申请和履约冻结"));
            if (result.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN) {
                executionFailures.put(current.orderId(), "REFUND_EXECUTION_RESULT_UNKNOWN");
            } else {
                executionFailures.put(current.orderId(), result.message());
            }
            throw new IllegalStateException("refund payment execution did not complete: "
                + result.status());
        });
        RefundSubmissionResult after = findApplicationByApproval(approval.getId());
        ApprovalRequest approvalAfter = approvalService.find(approval.getId()).orElse(approval);
        if (!completed) {
            return executionConflict(after == null ? application : after, approvalAfter,
                "退款执行租约未取得，未重复调用支付渠道");
        }
        RefundPaymentGateway.RefundPaymentResult result = outcome.get();
        if (result != null && result.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN) {
            return executionFromCurrent(after == null ? application : after, approvalAfter,
                RefundExecutionResult.Status.UNKNOWN, "退款渠道结果无法确认，已保留履约冻结并转人工处理");
        }
        if (result != null && result.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNAVAILABLE) {
            return executionFromCurrent(after == null ? application : after, approvalAfter,
                RefundExecutionResult.Status.UNAVAILABLE, "退款支付渠道未配置，未执行资金动作");
        }
        return executionFromCurrent(after == null ? application : after, approvalAfter, null, null);
    }

    private RefundExecutionResult reconcileAccepted(RefundSubmissionResult application,
                                                     ApprovalRequest approval,
                                                     RefundPaymentGateway.RefundPaymentResult result) {
        boolean completed = approvalService.executeApproved(approval.getId(), 3,
            application.idempotencyKey(), context -> {
                RefundSubmissionResult current = findApplicationByApproval(approval.getId());
                applications.put(current.orderId(), withApplicationStatus(current,
                    RefundApplicationStatus.EXECUTED,
                    "退款已提交支付渠道处理，凭证已记录；不承诺银行即时到账"));
                executionReferences.put(current.orderId(), result.reference());
                executionFailures.remove(current.orderId());
                activeFreezes.remove(current.orderId());
            });
        RefundSubmissionResult after = findApplicationByApproval(approval.getId());
        ApprovalRequest approvalAfter = approvalService.find(approval.getId()).orElse(approval);
        return !completed
            ? executionFromCurrent(after == null ? application : after, approvalAfter,
                RefundExecutionResult.Status.UNKNOWN, "渠道已受理但本地执行状态待恢复")
            : executionFromCurrent(after == null ? application : after, approvalAfter, null, null);
    }

    private RefundPaymentGateway.RefundPaymentResult queryPayment(String idempotencyKey) {
        try {
            RefundPaymentGateway.RefundPaymentResult result = paymentGateway.query(idempotencyKey);
            return result == null
                ? new RefundPaymentGateway.RefundPaymentResult(
                    RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null,
                    "支付渠道未返回查询结果") : result;
        } catch (Exception e) {
            return new RefundPaymentGateway.RefundPaymentResult(
                RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null,
                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private RefundExecutionResult executionFromCurrent(RefundSubmissionResult application,
                                                        ApprovalRequest approval,
                                                        RefundExecutionResult.Status overrideStatus,
                                                        String message) {
        RefundExecutionResult.Status status = overrideStatus;
        if (status == null) {
            status = switch (application.applicationStatus()) {
                case EXECUTED -> RefundExecutionResult.Status.EXECUTED;
                case EXECUTION_FAILED -> "REFUND_EXECUTION_RESULT_UNKNOWN".equals(
                    executionFailures.get(application.orderId()))
                    ? RefundExecutionResult.Status.UNKNOWN : RefundExecutionResult.Status.EXECUTION_FAILED;
                default -> RefundExecutionResult.Status.CONFLICT;
            };
        }
        if (message == null) {
            message = switch (status) {
                case EXECUTED -> "退款已提交支付渠道处理，凭证已记录；不承诺银行即时到账";
                case UNKNOWN -> "退款执行结果暂时无法确认，已保留履约冻结并转人工处理";
                case EXECUTION_FAILED -> "退款执行失败，已保留原申请和履约冻结，可继续恢复或转人工";
                case UNAVAILABLE -> "退款支付渠道不可用，未确认资金执行结果";
                default -> "退款当前不满足执行条件";
            };
        }
        return new RefundExecutionResult(status, approval.getId(), application.orderId(), application.refundNo(),
            application.applicationStatus(), approval.getStatus(), approval.getExecutionStatus(),
            application.idempotencyKey(), executionReferences.get(application.orderId()),
            application.policyVersion(), application.ruleIds(), message);
    }

    private RefundExecutionResult executionNotFound(String approvalId, String message) {
        return new RefundExecutionResult(RefundExecutionResult.Status.NOT_FOUND, approvalId, null, null,
            null, null, null, null, null, null, java.util.List.of(), message);
    }

    private RefundExecutionResult executionConflict(RefundSubmissionResult application,
                                                    ApprovalRequest approval, String message) {
        return executionFromCurrent(application, approval, RefundExecutionResult.Status.CONFLICT, message);
    }

    private RefundProgressResult doQueryRefundProgressStatus(String orderId) {
        RefundSubmissionResult application = applications.get(orderId);
        if (application == null) {
            return new RefundProgressResult(RefundProgressResult.QueryStatus.NOT_FOUND, orderId,
                null, null, null, null, null, java.util.List.of(), "未查询到退款申请");
        }
        ApprovalRequest approval = application.approvalId() == null
            ? null : approvalService.find(application.approvalId()).orElse(null);
        return new RefundProgressResult(RefundProgressResult.QueryStatus.FOUND, orderId,
            application.refundNo(), application.applicationStatus(),
            approval == null ? null : approval.getStatus(),
            approval == null ? ExecutionStatus.NOT_APPLICABLE : approval.getExecutionStatus(),
            application.policyVersion(), application.ruleIds(), application.message(),
            executionReferences.get(orderId));
    }

    private RefundSubmissionResult findApplicationByApproval(String approvalId) {
        return applications.values().stream()
            .filter(application -> java.util.Objects.equals(approvalId, application.approvalId()))
            .findFirst().orElse(null);
    }

    private RefundSubmissionResult withApplicationStatus(RefundSubmissionResult current,
                                                         RefundApplicationStatus applicationStatus,
                                                         String message) {
        return new RefundSubmissionResult(current.status(), current.orderId(), current.refundNo(),
            current.approvalId(), applicationStatus, current.refundableAmount(), current.riskLevel(),
            current.policyVersion(), current.reasonCodes(), current.ruleIds(), message,
            current.idempotencyKey());
    }

    private RefundApprovalResult approvalResult(RefundApprovalResult.Status status,
                                                ApprovalRequest approval,
                                                RefundSubmissionResult application,
                                                String message) {
        return new RefundApprovalResult(status, approval.getId(), application.orderId(),
            application.refundNo(), application.applicationStatus(), approval.getStatus(),
            approval.getExecutionStatus(), application.policyVersion(), application.ruleIds(),
            approval.getOperator(), approval.getDecidedAtMs(), message);
    }

    private RefundApprovalResult approvalNotFound(String approvalId, String message) {
        return new RefundApprovalResult(RefundApprovalResult.Status.NOT_FOUND, approvalId, null,
            null, null, null, null, null, java.util.List.of(), null, 0L, message);
    }

    private RefundApprovalResult approvalConflict(RefundSubmissionResult application, String approvalId,
                                                  String operator, String message) {
        ApprovalRequest approval = approvalService.find(approvalId).orElse(null);
        return new RefundApprovalResult(RefundApprovalResult.Status.CONFLICT, approvalId,
            application.orderId(), application.refundNo(), application.applicationStatus(),
            approval == null ? null : approval.getStatus(),
            approval == null ? null : approval.getExecutionStatus(), application.policyVersion(),
            application.ruleIds(), operator, approval == null ? 0L : approval.getDecidedAtMs(), message);
    }

    private RefundEligibilityResult evaluate(MockOrder order) {
        RefundEligibilityResult result = policyEvaluator.evaluate(order.status(), order.amount());
        if (result.decision() == RefundEligibilityResult.Decision.ELIGIBLE && order.hasEffectiveRefund()) {
            return policyEvaluator.existingRefundRequiresReview();
        }
        return result;
    }

    private boolean owns(UserPrincipal principal, MockOrder order) {
        return principal.tenantId().equals(order.tenantId()) && principal.userId().equals(order.userId());
    }

    private boolean hasIdentity(UserPrincipal principal) {
        return principal != null && principal.tenantId() != null && !principal.tenantId().isBlank()
            && principal.userId() != null && !principal.userId().isBlank();
    }

    private RefundPreparationResult preparationFromEligibility(String orderId, MockOrder order,
                                                               RefundEligibilityResult eligibility) {
        RefundPreparationResult.Status status = eligibility.decision()
            == RefundEligibilityResult.Decision.INELIGIBLE
            ? RefundPreparationResult.Status.INELIGIBLE : RefundPreparationResult.Status.HUMAN_REVIEW;
        return new RefundPreparationResult(status, orderId, order.productName(),
            eligibility.refundableAmount(), null, eligibility.riskLevel(), eligibility.policyVersion(),
            eligibility.reasonCodes(), eligibility.ruleIds(), null, 0L, true, true);
    }

    private RefundPreparationResult preparationReview(String orderId, String code) {
        return new RefundPreparationResult(RefundPreparationResult.Status.HUMAN_REVIEW, orderId, null,
            null, null, RefundEligibilityResult.RiskLevel.NORMAL, RefundPolicyEvaluator.POLICY_VERSION,
            java.util.List.of(code), java.util.List.of(), null, 0L, true, true);
    }

    private RefundPreparationResult preparationHighRisk(String orderId, MockOrder order) {
        return new RefundPreparationResult(RefundPreparationResult.Status.HUMAN_REVIEW, orderId,
            order.productName(), null, null, RefundEligibilityResult.RiskLevel.HIGH,
            RefundPolicyEvaluator.POLICY_VERSION, java.util.List.of("REFUND_HIGH_RISK_REASON"),
            java.util.List.of(RefundRiskEvaluator.UNTRUSTED_REASON_RULE_ID), null, 0L, true, true);
    }

    private RefundPreparationResult preparationIneligible(String orderId, String code) {
        return new RefundPreparationResult(RefundPreparationResult.Status.INELIGIBLE, orderId, null,
            null, null, RefundEligibilityResult.RiskLevel.NORMAL, RefundPolicyEvaluator.POLICY_VERSION,
            java.util.List.of(code), java.util.List.of(), null, 0L, false, true);
    }

    private RefundSubmissionResult submissionFromEligibility(String orderId,
                                                             RefundEligibilityResult eligibility) {
        return new RefundSubmissionResult(
            RefundSubmissionResult.Status.HUMAN_REVIEW, orderId, null, null, null,
            eligibility.refundableAmount(), eligibility.riskLevel(), eligibility.policyVersion(),
            eligibility.reasonCodes(), eligibility.ruleIds(),
            "退款申请需要人工审核，当前未创建申请");
    }

    private RefundSubmissionResult submissionHighRisk(String orderId) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.HUMAN_REVIEW, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.HIGH,
            RefundPolicyEvaluator.POLICY_VERSION, java.util.List.of("REFUND_HIGH_RISK_REASON"),
            java.util.List.of(RefundRiskEvaluator.UNTRUSTED_REASON_RULE_ID),
            "原因说明触发高风险人工复核，未创建退款申请");
    }

    private RefundSubmissionResult submissionReview(String orderId, String code) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.HUMAN_REVIEW, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, java.util.List.of(code), java.util.List.of(),
            "退款申请未创建，已转人工审核");
    }

    private RefundSubmissionResult submissionNotFound(String orderId, String idempotencyKey) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.NOT_FOUND, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, java.util.List.of("REFUND_APPLICATION_NOT_FOUND"),
            java.util.List.of(), "未查询到该订单的活跃退款申请，可以使用同一幂等键安全重试", idempotencyKey);
    }

    private RefundSubmissionResult submissionUnknown(String orderId, String idempotencyKey, String code) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.UNKNOWN, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, java.util.List.of(code), java.util.List.of(),
            "退款申请结果暂时无法确认，未自动重复提交，已转人工处理", idempotencyKey);
    }

    private RefundSubmissionResult existingResult(RefundSubmissionResult existing, String idempotencyKey) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.EXISTING, existing.orderId(),
            existing.refundNo(), existing.approvalId(), existing.applicationStatus(),
            existing.refundableAmount(), existing.riskLevel(), existing.policyVersion(),
            existing.reasonCodes(), existing.ruleIds(), "已存在退款申请，未重复创建", idempotencyKey);
    }

    @Override
    public Mono<String> queryRefundProgress(String orderId) {
        log.info("mock refund progress query: orderId={}", orderId);
        return Mono.just("订单 " + orderId + " 的退款进度：待人工复核，预计 1 个工作日内处理完成。");
    }

    @Override
    public Mono<String> submitReturn(String orderId, String reason) {
        log.info("mock return submission: orderId={}", orderId);
        return Mono.just("已生成退货工单 RT" + System.currentTimeMillis() + "：订单=" + orderId + "，原因=" + reason
            + "。请在 7 天内将商品寄回，回寄地址与运费规则将以短信发送。");
    }

    @Override
    public Mono<String> submitExchange(String orderId, String reason, String newSpec) {
        log.info("mock exchange submission: orderId={}, newSpec={}", orderId, newSpec);
        return Mono.just("已生成换货工单 EX" + System.currentTimeMillis() + "：订单=" + orderId
            + "，换为「" + newSpec + "」，原因=" + reason + "。新规格有货，收到回寄商品后为您发出。");
    }

    @Override
    public Mono<String> checkPriceProtection(String orderId) {
        log.info("mock price protection check: orderId={}", orderId);
        return Mono.just("订单 " + orderId + " 价保校验：在价保有效期内，当前价低于下单价 30 元，"
            + "已为您生成价保补差工单，差价将退回原支付方式。");
    }

    @Override
    public Mono<String> requestInvoice(String orderId, String invoiceTitle) {
        log.info("mock invoice request: orderId={}", orderId);
        return Mono.just("已受理订单 " + orderId + " 的发票申请，抬头=「" + invoiceTitle + "」，"
            + "电子发票将于 24 小时内发送至您账户预留邮箱。");
    }

    private record MockOrder(String status, BigDecimal amount, String productName,
                             boolean hasEffectiveRefund, String userId, String tenantId) {
    }
}
