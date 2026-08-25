package com.richard.fyoung.customerwork.tool.backend;

import com.richard.fyoung.customerwork.capability.approval.ApprovalRequest;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ApprovalType;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.safety.security.UserIdentityContext;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import com.richard.fyoung.customerwork.tool.backend.entity.OrderDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundFulfillmentFreezeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundFulfillmentFreezeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 售后后端的 MyBatis-Plus 实现：退款/退货/换货工单真实落库到 {@code cw_refund}，发票申请落库到
 * {@code cw_invoice_request}，退款资格 / 价保联查 {@code cw_order}（订单存在且状态允许才可退）。
 *
 * <p>保留资金安全红线：{@code submitRefund} 只生成 {@code PENDING_REVIEW} 待人工复核申请，<b>不直接打款</b>，
 * 且退款申请、人工审批和履约冻结必须由同一事务提交。语义与 {@link MockAfterSalesBackend} 完全一致。
 * 工单落库走 {@link RefundMapper} /
 * {@link InvoiceRequestMapper} 的 BaseMapper insert，最近退款状态查询走 XML；联查订单走 {@link OrderMapper}。
 * 本类由 starter 的 {@code ToolBackendConfig} 在 {@code tool-backend.mode=jdbc} 时装配。</p>
 * @author owlzhangfq@gmail.com
 */
public class MybatisAfterSalesBackend implements AfterSalesBackend {

    private static final Logger log = LoggerFactory.getLogger(MybatisAfterSalesBackend.class);

    /** 售后工单类型：退款 / 退货 / 换货。 */
    private static final String TYPE_REFUND = "REFUND";
    private static final String TYPE_RETURN = "RETURN";
    private static final String TYPE_EXCHANGE = "EXCHANGE";

    /** 工单状态：待人工复核 / 已通过。 */
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String APPLICATION_PENDING_REVIEW = "PENDING_REVIEW";
    private static final String APPLICATION_EXECUTING = "EXECUTING";
    private static final String APPLICATION_EXECUTION_FAILED = "EXECUTION_FAILED";
    private static final String ACTIVE_MARKER = "ACTIVE";
    private static final String FREEZE_ACTIVE = "ACTIVE";
    private static final int MAX_EXECUTION_ATTEMPTS = 3;
    private static final String ORDER_REFUNDED_STATUS = "已退款";
    private static final String EXECUTION_UNKNOWN_REASON = "REFUND_EXECUTION_RESULT_UNKNOWN";

    private final RefundMapper refundMapper;
    private final InvoiceRequestMapper invoiceRequestMapper;
    private final OrderMapper orderMapper;
    private final RefundFulfillmentFreezeMapper freezeMapper;
    private final PendingApprovalService approvalService;
    private final CustomerWorkTransactionExecutor transactionExecutor;
    private final RefundConfirmationTokenService tokenService;
    private final RefundPaymentGateway paymentGateway;
    private final RefundPolicyEvaluator policyEvaluator = new RefundPolicyEvaluator();

    public MybatisAfterSalesBackend(RefundMapper refundMapper, InvoiceRequestMapper invoiceRequestMapper,
                                    OrderMapper orderMapper) {
        this(refundMapper, invoiceRequestMapper, orderMapper, null, null,
            CustomerWorkTransactionExecutor.DIRECT,
            new RefundConfirmationTokenService("task-02-development-only-secret"),
            RefundPaymentGateway.unavailable());
    }

    public MybatisAfterSalesBackend(RefundMapper refundMapper, InvoiceRequestMapper invoiceRequestMapper,
                                    OrderMapper orderMapper,
                                    RefundFulfillmentFreezeMapper freezeMapper,
                                    PendingApprovalService approvalService,
                                    CustomerWorkTransactionExecutor transactionExecutor,
                                    RefundConfirmationTokenService tokenService) {
        this(refundMapper, invoiceRequestMapper, orderMapper, freezeMapper, approvalService,
            transactionExecutor, tokenService, RefundPaymentGateway.unavailable());
    }

    public MybatisAfterSalesBackend(RefundMapper refundMapper, InvoiceRequestMapper invoiceRequestMapper,
                                    OrderMapper orderMapper,
                                    RefundFulfillmentFreezeMapper freezeMapper,
                                    PendingApprovalService approvalService,
                                    CustomerWorkTransactionExecutor transactionExecutor,
                                    RefundConfirmationTokenService tokenService,
                                    RefundPaymentGateway paymentGateway) {
        this.refundMapper = refundMapper;
        this.invoiceRequestMapper = invoiceRequestMapper;
        this.orderMapper = orderMapper;
        this.freezeMapper = freezeMapper;
        this.approvalService = approvalService;
        this.transactionExecutor = transactionExecutor == null
            ? CustomerWorkTransactionExecutor.DIRECT : transactionExecutor;
        this.tokenService = tokenService;
        this.paymentGateway = paymentGateway == null ? RefundPaymentGateway.unavailable() : paymentGateway;
    }

    @Override
    public Mono<RefundEligibilityResult> checkRefundEligibility(String orderId) {
        return Mono.fromSupplier(() -> doCheckRefundEligibility(orderId));
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

    @Override
    public Mono<String> queryRefundProgress(String orderId) {
        return Mono.fromSupplier(() -> doQueryRefundProgress(orderId));
    }

    @Override
    public Mono<String> submitReturn(String orderId, String reason) {
        return Mono.fromSupplier(() -> doSubmitReturn(orderId, reason));
    }

    @Override
    public Mono<String> submitExchange(String orderId, String reason, String newSpec) {
        return Mono.fromSupplier(() -> doSubmitExchange(orderId, reason, newSpec));
    }

    @Override
    public Mono<String> checkPriceProtection(String orderId) {
        return Mono.fromSupplier(() -> doCheckPriceProtection(orderId));
    }

    @Override
    public Mono<String> requestInvoice(String orderId, String invoiceTitle) {
        return Mono.fromSupplier(() -> doRequestInvoice(orderId, invoiceTitle));
    }

    /** 退款资格校验：Backend 根据订单支付/履约事实和金额裁决，不接受 Agent 资格标记。 */
    private RefundEligibilityResult doCheckRefundEligibility(String orderId) {
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return policyEvaluator.orderNotFound();
            }
            RefundEligibilityResult result = policyEvaluator.evaluate(order.getStatus(), order.getAmount());
            if (result.decision() == RefundEligibilityResult.Decision.ELIGIBLE
                    && refundMapper.countEffectiveRefunds(orderId, TYPE_REFUND) > 0) {
                return policyEvaluator.existingRefundRequiresReview();
            }
            return result;
        } catch (Exception e) {
            log.error("refund eligibility check failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-ELIGIBILITY-FAIL", orderId, e);
            return policyEvaluator.unavailable();
        }
    }

    /** 退款准备只读：服务端查订单、校验归属、计算全额金额并签发短期确认令牌。 */
    private RefundPreparationResult doPrepareRefund(String orderId, String reasonCode, String reasonText) {
        RefundReasonValidator.Validation reason = RefundReasonValidator.validate(reasonCode, reasonText);
        if (!reason.valid()) {
            return preparationReview(orderId, reason.errorCode());
        }
        UserPrincipal principal = currentIdentity();
        if (principal == null) {
            return preparationReview(orderId, "REFUND_IDENTITY_REQUIRED");
        }
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return preparationIneligible(orderId, "ORDER_NOT_FOUND");
            }
            if (!owns(principal, order)) {
                return preparationReview(orderId, "REFUND_ORDER_OWNERSHIP_MISMATCH");
            }
            RefundEligibilityResult eligibility = eligibilityFor(orderId, order);
            if (eligibility.decision() != RefundEligibilityResult.Decision.ELIGIBLE) {
                return preparationFromEligibility(orderId, order, eligibility);
            }
            if (RefundRiskEvaluator.reasonRequiresHumanReview(reason.text())) {
                return preparationHighRisk(orderId, order);
            }
            RefundDO latest = refundMapper.findLatestRefund(orderId, TYPE_REFUND);
            if (latest != null && isRejected(latest)) {
                return preparationReview(orderId, "REFUND_APPLICATION_REJECTED");
            }
            if (tokenService == null) {
                return preparationReview(orderId, "REFUND_CONFIRMATION_UNAVAILABLE");
            }
            String token = tokenService.issue(principal, orderId, eligibility.refundableAmount(),
                eligibility.policyVersion());
            return new RefundPreparationResult(
                RefundPreparationResult.Status.READY, orderId, order.getProductName(),
                eligibility.refundableAmount(), reason.code().name(), eligibility.riskLevel(),
                eligibility.policyVersion(), eligibility.reasonCodes(), eligibility.ruleIds(), token,
                tokenService.expiresAtMs(), true, true);
        } catch (Exception e) {
            log.error("refund preparation failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-REFUND-PREPARE-FAIL", orderId, e);
            return preparationReview(orderId, "REFUND_POLICY_UNAVAILABLE");
        }
    }

    /**
     * 退款提交：再次查询归属与资格，再校验确认令牌，最后在同一事务内写入申请、审批和履约冻结。
     */
    private RefundSubmissionResult doSubmitRefund(String orderId, String reasonCode,
                                                   String reasonText, String confirmationToken) {
        RefundReasonValidator.Validation reason = RefundReasonValidator.validate(reasonCode, reasonText);
        if (!reason.valid()) {
            return submissionReview(orderId, reason.errorCode());
        }
        UserPrincipal principal = currentIdentity();
        if (principal == null) {
            return submissionReview(orderId, "REFUND_IDENTITY_REQUIRED");
        }
        if (freezeMapper == null || approvalService == null || tokenService == null) {
            return submissionFailed(orderId, "REFUND_APPLICATION_UNAVAILABLE");
        }
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return submissionReview(orderId, "ORDER_NOT_FOUND");
            }
            if (!owns(principal, order)) {
                return submissionReview(orderId, "REFUND_ORDER_OWNERSHIP_MISMATCH");
            }
            String idempotencyKey = RefundIdempotencyKey.fullRefund(principal.tenantId(), orderId);
            RefundDO active = refundMapper.findByIdempotencyKey(idempotencyKey);
            if (active == null) {
                active = refundMapper.findActiveRefund(orderId, TYPE_REFUND);
            }
            if (active != null) {
                if (isRejected(active)) {
                    return submissionReview(orderId, "REFUND_APPLICATION_REJECTED");
                }
                return existingResult(active, orderId, idempotencyKey);
            }
            RefundDO latest = refundMapper.findLatestRefund(orderId, TYPE_REFUND);
            if (latest != null && isRejected(latest)) {
                return submissionReview(orderId, "REFUND_APPLICATION_REJECTED");
            }
            RefundEligibilityResult eligibility = eligibilityFor(orderId, order);
            if (eligibility.decision() != RefundEligibilityResult.Decision.ELIGIBLE) {
                return submissionFromEligibility(orderId, eligibility);
            }
            if (RefundRiskEvaluator.reasonRequiresHumanReview(reason.text())) {
                return submissionHighRisk(orderId);
            }
            RefundConfirmationTokenService.Verification verification = tokenService.verify(
                confirmationToken, principal, orderId, eligibility.refundableAmount(),
                eligibility.policyVersion());
            if (!verification.valid()) {
                return submissionReview(orderId, verification.errorCode());
            }
            try {
                return transactionExecutor.execute(() -> createRefundApplication(
                    order, orderId, idempotencyKey, reason, principal, eligibility));
            } catch (RuntimeException duplicateOrWriteFailure) {
                // 并发请求可能在预检之后先占用唯一键；读回成功者，避免把幂等命中报成系统故障。
                RefundDO raced = refundMapper.findByIdempotencyKey(idempotencyKey);
                if (raced == null) {
                    raced = refundMapper.findActiveRefund(orderId, TYPE_REFUND);
                }
                if (raced != null) {
                    return existingResult(raced, orderId, idempotencyKey);
                }
                if (RefundWriteUncertainty.isUncertain(duplicateOrWriteFailure)) {
                    return submissionUnknown(orderId, idempotencyKey, "REFUND_WRITE_RESULT_UNKNOWN");
                }
                throw duplicateOrWriteFailure;
            }
        } catch (Exception e) {
            log.error("refund application failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-REFUND-SUBMIT-FAIL", orderId, e);
            if (RefundWriteUncertainty.isUncertain(e)) {
                return submissionUnknown(orderId, safeIdempotencyKey(principal, orderId),
                    "REFUND_WRITE_RESULT_UNKNOWN");
            }
            return submissionFailed(orderId, "REFUND_APPLICATION_FAILED");
        }
    }

    private RefundSubmissionResult doQueryRefundApplication(String orderId) {
        UserPrincipal principal = currentIdentity();
        if (principal == null) {
            return submissionReview(orderId, "REFUND_IDENTITY_REQUIRED");
        }
        String idempotencyKey;
        try {
            idempotencyKey = RefundIdempotencyKey.fullRefund(principal.tenantId(), orderId);
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return submissionReview(orderId, "ORDER_NOT_FOUND");
            }
            if (!owns(principal, order)) {
                return submissionReview(orderId, "REFUND_ORDER_OWNERSHIP_MISMATCH");
            }
            RefundDO active = refundMapper.findByIdempotencyKey(idempotencyKey);
            if (active == null) {
                active = refundMapper.findActiveRefund(orderId, TYPE_REFUND);
            }
            if (active == null) {
                RefundDO latest = refundMapper.findLatestRefund(orderId, TYPE_REFUND);
                if (latest != null && isRejected(latest)) {
                    return existingResult(latest, orderId, idempotencyKey);
                }
            }
            return active == null
                ? submissionNotFound(orderId, idempotencyKey)
                : existingResult(active, orderId, idempotencyKey);
        } catch (Exception e) {
            log.error("refund application query failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-REFUND-APPLICATION-QUERY-FAIL", orderId, e);
            return submissionUnknown(orderId, safeIdempotencyKey(principal, orderId),
                "REFUND_APPLICATION_RESULT_UNKNOWN");
        }
    }

    private RefundApprovalResult doApproveRefund(String approvalId, String operator, String note) {
        RefundDO refund = refundMapper.findByApprovalId(approvalId);
        ApprovalRequest approval = approvalService.find(approvalId).orElse(null);
        if (refund == null || approval == null) {
            return approvalNotFound(approvalId, "未查询到退款审批单");
        }
        if (approval.getType() != ApprovalType.REFUND
            || !isPendingReview(refund) || approval.getStatus() != ApprovalStatus.PENDING) {
            return approvalConflict(refund, approval, operator, "退款审批状态冲突，未覆盖已有决策");
        }
        try {
            return transactionExecutor.execute(() -> {
                ApprovalRequest decided = approvalService.approveDecisionOnly(approvalId, operator, note);
                requireOne(refundMapper.updateDecisionState(refund.getRefundNo(), APPLICATION_PENDING_REVIEW,
                    RefundApplicationStatus.APPROVED_PENDING_EXECUTION.name(), STATUS_APPROVED,
                    ACTIVE_MARKER), "refund approval state update");
                return approvalResult(RefundApprovalResult.Status.APPROVED, decided, refund,
                    RefundApplicationStatus.APPROVED_PENDING_EXECUTION,
                    "退款申请已通过人工审核，等待退款执行；不会自动宣称已到账");
            });
        } catch (IllegalStateException e) {
            if (isApprovalConflict(e)) {
                log.warn("refund approval conflict, code={}, approvalId={}",
                    "REFUND-APPROVAL-CONFLICT", approvalId, e);
                return approvalConflict(refund, approvalService.find(approvalId).orElse(approval), operator,
                    "审批已被其他操作员处理或状态更新冲突");
            }
            log.error("refund approval transaction failed, code={}, approvalId={}",
                "REFUND-APPROVAL-FAIL", approvalId, e);
            return approvalFailure(refund, approval, operator, "退款审批未完成，未改变申请状态");
        } catch (RuntimeException e) {
            log.error("refund approval failed, code={}, approvalId={}",
                "REFUND-APPROVAL-FAIL", approvalId, e);
            return approvalFailure(refund, approval, operator, "退款审批未完成，未改变申请状态");
        }
    }

    private RefundApprovalResult doDenyRefund(String approvalId, String operator, String note) {
        RefundDO refund = refundMapper.findByApprovalId(approvalId);
        ApprovalRequest approval = approvalService.find(approvalId).orElse(null);
        if (refund == null || approval == null) {
            return approvalNotFound(approvalId, "未查询到退款审批单");
        }
        if (approval.getType() != ApprovalType.REFUND
            || !isPendingReview(refund) || approval.getStatus() != ApprovalStatus.PENDING) {
            return approvalConflict(refund, approval, operator, "退款审批状态冲突，未覆盖已有决策");
        }
        try {
            return transactionExecutor.execute(() -> {
                ApprovalRequest decided = approvalService.denyDecisionOnly(approvalId, operator, note);
                requireOne(refundMapper.updateDecisionState(refund.getRefundNo(), APPLICATION_PENDING_REVIEW,
                    RefundApplicationStatus.REJECTED.name(), "DENIED", null),
                    "refund rejection state update");
                RefundFulfillmentFreezeDO freeze = freezeMapper.findActiveByRefundNo(refund.getRefundNo());
                if (freeze != null) {
                    requireOne(freezeMapper.release(freeze.getFreezeId(), System.currentTimeMillis()),
                        "refund fulfillment freeze release");
                    if (freeze.getPreviousOrderStatus() != null
                        && !freeze.getPreviousOrderStatus().isBlank()) {
                        requireOne(orderMapper.updateStatus(refund.getOrderId(),
                            freeze.getPreviousOrderStatus()), "refund order status restore");
                    }
                }
                return approvalResult(RefundApprovalResult.Status.REJECTED, decided, refund,
                    RefundApplicationStatus.REJECTED,
                    "退款申请已被人工拒绝，履约冻结已释放，可联系人工申诉；不会自动重新提交");
            });
        } catch (IllegalStateException e) {
            if (isApprovalConflict(e)) {
                log.warn("refund rejection conflict, code={}, approvalId={}",
                    "REFUND-REJECTION-CONFLICT", approvalId, e);
                return approvalConflict(refund, approvalService.find(approvalId).orElse(approval), operator,
                    "审批已被其他操作员处理或状态更新冲突");
            }
            log.error("refund rejection transaction failed, code={}, approvalId={}",
                "REFUND-REJECTION-FAIL", approvalId, e);
            return approvalFailure(refund, approval, operator, "退款拒绝未完成，申请与冻结保持原状态");
        } catch (RuntimeException e) {
            log.error("refund rejection failed, code={}, approvalId={}",
                "REFUND-REJECTION-FAIL", approvalId, e);
            return approvalFailure(refund, approval, operator, "退款拒绝未完成，申请与冻结保持原状态");
        }
    }

    /**
     * 受控执行已批准退款。该方法不暴露为 Agent Tool，只能由支付执行服务/运维恢复任务调用。
     */
    private RefundExecutionResult doExecuteRefund(String approvalId, boolean retry) {
        RefundDO refund = refundMapper.findByApprovalId(approvalId);
        ApprovalRequest approval = approvalService == null
            ? null : approvalService.find(approvalId).orElse(null);
        if (refund == null || approval == null) {
            return executionNotFound(approvalId, "未查询到退款执行申请");
        }
        if (approval.getType() != ApprovalType.REFUND || approval.getStatus() != ApprovalStatus.APPROVED) {
            return executionConflict(refund, approval, "只有人工批准的退款申请可以执行");
        }
        RefundApplicationStatus applicationStatus = parseApplicationStatus(refund.getApplicationStatus());
        if (applicationStatus == RefundApplicationStatus.EXECUTED) {
            return executionFromCurrent(refund, approval, RefundExecutionResult.Status.EXECUTED,
                "退款已由支付渠道明确受理，不会重复执行");
        }
        if (!retry && applicationStatus != RefundApplicationStatus.APPROVED_PENDING_EXECUTION) {
            return executionConflict(refund, approval, "退款申请尚未处于可执行状态");
        }
        if (retry && applicationStatus == RefundApplicationStatus.EXECUTING
            && approval.getExecutionStatus() == ExecutionStatus.EXECUTE_FAILED) {
            RefundPaymentGateway.RefundPaymentResult query = queryPayment(refund.getIdempotencyKey());
            if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED) {
                return reconcileAccepted(refund, approval, query, APPLICATION_EXECUTING);
            }
            if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN) {
                return executionFromCurrent(refund, approval, RefundExecutionResult.Status.UNKNOWN,
                    "退款渠道结果仍无法确认，保留履约冻结并转人工核对");
            }
            if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNAVAILABLE) {
                return executionFromCurrent(refund, approval, RefundExecutionResult.Status.UNAVAILABLE,
                    "退款渠道查询不可用，未自动重复执行");
            }
            // 渠道明确无记录/失败时，继续使用原申请和原幂等键恢复。
        } else if (retry && applicationStatus == RefundApplicationStatus.EXECUTING) {
            return executionFromCurrent(refund, approval, RefundExecutionResult.Status.UNKNOWN,
                "退款执行租约仍在进行，暂不重复调用支付渠道");
        }
        if (retry && applicationStatus != RefundApplicationStatus.APPROVED_PENDING_EXECUTION
            && applicationStatus != RefundApplicationStatus.EXECUTION_FAILED
            && applicationStatus != RefundApplicationStatus.EXECUTING) {
            return executionConflict(refund, approval, "退款申请当前状态不允许恢复执行");
        }
        if (applicationStatus == RefundApplicationStatus.EXECUTION_FAILED
            && isUnknownExecution(refund)) {
            RefundPaymentGateway.RefundPaymentResult query = queryPayment(refund.getIdempotencyKey());
            if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED) {
                return reconcileAccepted(refund, approval, query, APPLICATION_EXECUTION_FAILED);
            }
            if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN) {
                return executionFromCurrent(refund, approval, RefundExecutionResult.Status.UNKNOWN,
                    "退款渠道结果仍无法确认，保留履约冻结并转人工核对");
            }
            if (query.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNAVAILABLE) {
                return executionFromCurrent(refund, approval, RefundExecutionResult.Status.UNAVAILABLE,
                    "退款渠道查询不可用，未自动重复执行");
            }
            // NOT_FOUND 或渠道明确失败：原申请仍可按同一幂等键执行/重试。
        }
        return startPaymentExecution(refund, approval);
    }

    private RefundExecutionResult startPaymentExecution(RefundDO refund, ApprovalRequest approval) {
        String idempotencyKey = refund.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return executionConflict(refund, approval, "退款申请缺少稳定支付幂等键，转人工处理");
        }
        java.util.concurrent.atomic.AtomicReference<RefundPaymentGateway.RefundPaymentResult> outcome =
            new java.util.concurrent.atomic.AtomicReference<>();
        try {
            boolean completed = approvalService.executeApproved(approval.getId(), MAX_EXECUTION_ATTEMPTS,
                idempotencyKey, context -> {
                    RefundDO current = refundMapper.findByApprovalId(approval.getId());
                    if (current == null) {
                        throw new IllegalStateException("refund application disappeared during execution");
                    }
                    String expectedStatus = current.getApplicationStatus();
                    transactionExecutor.execute(() -> {
                        requireOne(refundMapper.updateExecutionState(current.getRefundNo(), expectedStatus,
                            APPLICATION_EXECUTING, STATUS_APPROVED, ACTIVE_MARKER, null, null, 0L),
                            "refund execution lease state update");
                        return null;
                    });

                    RefundPaymentGateway.RefundPaymentResult result;
                    try {
                        result = paymentGateway.execute(new RefundPaymentGateway.RefundPaymentRequest(
                            current.getRefundNo(), current.getOrderId(), current.getAmount(), idempotencyKey));
                    } catch (Exception e) {
                        result = new RefundPaymentGateway.RefundPaymentResult(
                            RefundPaymentGateway.RefundPaymentResult.Status.FAILED, null,
                            safeFailureMessage(e));
                    }
                    if (result == null) {
                        result = new RefundPaymentGateway.RefundPaymentResult(
                            RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null,
                            "支付渠道未返回结果");
                    }
                    outcome.set(result);
                    if (result.status() == RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED) {
                        markRefundExecuted(current, result, APPLICATION_EXECUTING);
                        return;
                    }
                    markRefundExecutionFailed(current, result);
                    throw new IllegalStateException("refund payment execution did not complete: "
                        + result.status());
                });
            RefundDO after = refundMapper.findByApprovalId(approval.getId());
            ApprovalRequest approvalAfter = approvalService.find(approval.getId()).orElse(approval);
            if (!completed) {
                return executionConflict(after == null ? refund : after, approvalAfter,
                    "退款执行租约未取得，未重复调用支付渠道");
            }
            RefundPaymentGateway.RefundPaymentResult paymentResult = outcome.get();
            if (paymentResult != null
                && paymentResult.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN) {
                return executionFromCurrent(after == null ? refund : after, approvalAfter,
                    RefundExecutionResult.Status.UNKNOWN,
                    "退款渠道结果无法确认，已保留履约冻结并等待查询");
            }
            if (paymentResult != null
                && paymentResult.status() == RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED
                && (after == null || parseApplicationStatus(after.getApplicationStatus())
                    != RefundApplicationStatus.EXECUTED)) {
                return executionFromCurrent(after == null ? refund : after, approvalAfter,
                    RefundExecutionResult.Status.UNKNOWN,
                    "退款渠道已受理但本地状态尚未完成，已保留履约冻结并等待对账");
            }
            if (paymentResult != null
                && paymentResult.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNAVAILABLE) {
                return executionFromCurrent(after == null ? refund : after, approvalAfter,
                    RefundExecutionResult.Status.UNAVAILABLE,
                    "退款支付渠道未配置，未执行资金动作");
            }
            return executionFromCurrent(after == null ? refund : after, approvalAfter, null, null);
        } catch (RuntimeException e) {
            log.error("refund execution orchestration failed, code={}, approvalId={}",
                "REFUND-EXECUTION-ORCHESTRATION-FAIL", approval.getId(), e);
            RefundDO after = refundMapper.findByApprovalId(approval.getId());
            ApprovalRequest approvalAfter = approvalService.find(approval.getId()).orElse(approval);
            return executionFromCurrent(after == null ? refund : after, approvalAfter,
                RefundExecutionResult.Status.UNKNOWN, "退款执行状态暂时无法确认，已转人工处理");
        }
    }

    private RefundExecutionResult reconcileAccepted(RefundDO refund, ApprovalRequest approval,
                                                     RefundPaymentGateway.RefundPaymentResult result,
                                                     String expectedApplicationStatus) {
        try {
            boolean completed = approvalService.executeApproved(approval.getId(), MAX_EXECUTION_ATTEMPTS,
                refund.getIdempotencyKey(), context -> markRefundExecuted(refund, result,
                    expectedApplicationStatus));
            RefundDO after = refundMapper.findByApprovalId(approval.getId());
            ApprovalRequest approvalAfter = approvalService.find(approval.getId()).orElse(approval);
            if (!completed) {
                return executionFromCurrent(after == null ? refund : after, approvalAfter,
                    RefundExecutionResult.Status.UNKNOWN, "渠道已受理但本地执行状态待恢复");
            }
            return executionFromCurrent(after == null ? refund : after, approvalAfter, null, null);
        } catch (RuntimeException e) {
            log.error("refund accepted result reconciliation failed, code={}, approvalId={}",
                "REFUND-EXECUTION-RECONCILE-FAIL", approval.getId(), e);
            return executionFromCurrent(refund, approval, RefundExecutionResult.Status.UNKNOWN,
                "渠道已受理但本地状态暂未完成，已转人工核对");
        }
    }

    private void markRefundExecuted(RefundDO refund,
                                    RefundPaymentGateway.RefundPaymentResult result,
                                    String expectedApplicationStatus) {
        transactionExecutor.execute(() -> {
            requireOne(refundMapper.updateExecutionState(refund.getRefundNo(), expectedApplicationStatus,
                RefundApplicationStatus.EXECUTED.name(), STATUS_APPROVED, null, result.reference(), null,
                System.currentTimeMillis()), "refund execution success state update");
            RefundFulfillmentFreezeDO freeze = freezeMapper == null
                ? null : freezeMapper.findActiveByRefundNo(refund.getRefundNo());
            if (freeze != null) {
                requireOne(freezeMapper.release(freeze.getFreezeId(), System.currentTimeMillis()),
                    "refund execution freeze release");
            }
            requireOne(orderMapper.updateStatus(refund.getOrderId(), ORDER_REFUNDED_STATUS),
                "refund order refunded state update");
            return null;
        });
    }

    private void markRefundExecutionFailed(RefundDO refund,
                                           RefundPaymentGateway.RefundPaymentResult result) {
        String reason = result.status() == RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN
            ? EXECUTION_UNKNOWN_REASON : safeGatewayMessage(result);
        transactionExecutor.execute(() -> {
            requireOne(refundMapper.updateExecutionState(refund.getRefundNo(), APPLICATION_EXECUTING,
                APPLICATION_EXECUTION_FAILED, STATUS_APPROVED, ACTIVE_MARKER, null, reason, 0L),
                "refund execution failure state update");
            return null;
        });
    }

    private RefundPaymentGateway.RefundPaymentResult queryPayment(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return new RefundPaymentGateway.RefundPaymentResult(
                RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null,
                "退款申请缺少支付幂等键");
        }
        try {
            RefundPaymentGateway.RefundPaymentResult result = paymentGateway.query(idempotencyKey);
            return result == null
                ? new RefundPaymentGateway.RefundPaymentResult(
                    RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null,
                    "支付渠道未返回查询结果") : result;
        } catch (Exception e) {
            return new RefundPaymentGateway.RefundPaymentResult(
                RefundPaymentGateway.RefundPaymentResult.Status.UNKNOWN, null, safeFailureMessage(e));
        }
    }

    private boolean isUnknownExecution(RefundDO refund) {
        return EXECUTION_UNKNOWN_REASON.equals(refund.getExecutionFailureReason());
    }

    private RefundExecutionResult executionFromCurrent(RefundDO refund, ApprovalRequest approval,
                                                        RefundExecutionResult.Status overrideStatus,
                                                        String overrideMessage) {
        RefundApplicationStatus applicationStatus = parseApplicationStatus(refund.getApplicationStatus());
        RefundExecutionResult.Status status = overrideStatus;
        String message = overrideMessage;
        if (status == null) {
            status = switch (applicationStatus) {
                case EXECUTED -> RefundExecutionResult.Status.EXECUTED;
                case EXECUTION_FAILED -> isUnknownExecution(refund)
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
        return new RefundExecutionResult(status, approval.getId(), refund.getOrderId(), refund.getRefundNo(),
            applicationStatus, approval.getStatus(), approval.getExecutionStatus(), refund.getIdempotencyKey(),
            refund.getExecutionReference(), refund.getPolicyVersion(), parseRuleIds(refund.getRuleIdsJson()),
            message);
    }

    private RefundExecutionResult executionNotFound(String approvalId, String message) {
        return new RefundExecutionResult(RefundExecutionResult.Status.NOT_FOUND, approvalId, null, null,
            null, null, null, null, null, null, List.of(), message);
    }

    private RefundExecutionResult executionConflict(RefundDO refund, ApprovalRequest approval,
                                                    String message) {
        return executionFromCurrent(refund, approval, RefundExecutionResult.Status.CONFLICT, message);
    }

    private String safeGatewayMessage(RefundPaymentGateway.RefundPaymentResult result) {
        if (result.message() == null || result.message().isBlank()) {
            return "REFUND_PAYMENT_EXECUTION_FAILED";
        }
        return result.message().length() > 256 ? result.message().substring(0, 256) : result.message();
    }

    private String safeFailureMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private RefundProgressResult doQueryRefundProgressStatus(String orderId) {
        try {
            RefundDO refund = refundMapper.findLatestRefund(orderId, TYPE_REFUND);
            if (refund == null) {
                return new RefundProgressResult(RefundProgressResult.QueryStatus.NOT_FOUND, orderId,
                    null, null, null, null, null, List.of(), "未查询到退款申请");
            }
            ApprovalRequest approval = refund.getApprovalId() == null
                ? null : approvalService.find(refund.getApprovalId()).orElse(null);
            return new RefundProgressResult(RefundProgressResult.QueryStatus.FOUND, orderId,
                refund.getRefundNo(), parseApplicationStatus(refund.getApplicationStatus()),
                approval == null ? null : approval.getStatus(),
                approval == null ? ExecutionStatus.NOT_APPLICABLE : approval.getExecutionStatus(),
                refund.getPolicyVersion(), parseRuleIds(refund.getRuleIdsJson()),
                "退款申请、人工审批和资金执行状态已分开记录", refund.getExecutionReference());
        } catch (Exception e) {
            log.error("refund progress query failed, code={}, orderId={}",
                "REFUND-PROGRESS-QUERY-FAIL", orderId, e);
            return new RefundProgressResult(RefundProgressResult.QueryStatus.UNKNOWN, orderId,
                null, null, null, null, null, List.of(), "退款进度暂时无法确认，已转人工处理");
        }
    }

    private RefundApprovalResult approvalResult(RefundApprovalResult.Status status,
                                                ApprovalRequest approval, RefundDO refund,
                                                RefundApplicationStatus applicationStatus,
                                                String message) {
        return new RefundApprovalResult(status, approval.getId(), refund.getOrderId(),
            refund.getRefundNo(), applicationStatus, approval.getStatus(),
            approval.getExecutionStatus(), refund.getPolicyVersion(), parseRuleIds(refund.getRuleIdsJson()),
            approval.getOperator(), approval.getDecidedAtMs(), message);
    }

    private RefundApprovalResult approvalNotFound(String approvalId, String message) {
        return new RefundApprovalResult(RefundApprovalResult.Status.NOT_FOUND, approvalId, null,
            null, null, null, null, null, List.of(), null, 0L, message);
    }

    private RefundApprovalResult approvalConflict(RefundDO refund, ApprovalRequest approval,
                                                  String operator, String message) {
        return new RefundApprovalResult(RefundApprovalResult.Status.CONFLICT, approval.getId(),
            refund.getOrderId(), refund.getRefundNo(), parseApplicationStatus(refund.getApplicationStatus()),
            approval.getStatus(), approval.getExecutionStatus(), refund.getPolicyVersion(),
            parseRuleIds(refund.getRuleIdsJson()), operator, approval.getDecidedAtMs(), message);
    }

    private RefundApprovalResult approvalFailure(RefundDO refund, ApprovalRequest approval,
                                                 String operator, String message) {
        return new RefundApprovalResult(RefundApprovalResult.Status.FAILED, approval.getId(),
            refund.getOrderId(), refund.getRefundNo(), parseApplicationStatus(refund.getApplicationStatus()),
            approval.getStatus(), approval.getExecutionStatus(), refund.getPolicyVersion(),
            parseRuleIds(refund.getRuleIdsJson()), operator, approval.getDecidedAtMs(), message);
    }

    private RefundSubmissionResult createRefundApplication(OrderDO order, String orderId,
                                                            String idempotencyKey,
                                                            RefundReasonValidator.Validation reason,
                                                            UserPrincipal principal,
                                                            RefundEligibilityResult eligibility) {
        long now = System.currentTimeMillis();
        String refundNo = "RF-" + UUID.randomUUID();
        RefundDO refund = new RefundDO();
        refund.setRefundNo(refundNo);
        refund.setOrderId(orderId);
        refund.setType(TYPE_REFUND);
        refund.setStatus(STATUS_PENDING);
        refund.setApplicationStatus(APPLICATION_PENDING_REVIEW);
        refund.setActiveMarker(ACTIVE_MARKER);
        refund.setUserId(principal.userId());
        refund.setIdempotencyKey(idempotencyKey);
        refund.setAmount(eligibility.refundableAmount());
        refund.setReason(reason.code().name() + ":" + reason.text());
        refund.setCreatedAtMs(now);
        refund.setPolicyVersion(eligibility.policyVersion());
        refund.setRiskLevel(eligibility.riskLevel().name());
        refund.setRuleIdsJson(toJson(eligibility.ruleIds()));
        requireOne(refundMapper.insert(refund), "refund application insert");

        String sessionId = RefundInvocationContext.get();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "refund-backend";
        }
        ApprovalRequest approval = approvalService.submit(ApprovalType.REFUND, sessionId,
            principal.userId(), orderId, eligibility.refundableAmount().toPlainString(),
            reason.code().name() + ":" + reason.text());

        RefundFulfillmentFreezeDO freeze = new RefundFulfillmentFreezeDO();
        freeze.setFreezeId("FF-" + UUID.randomUUID());
        freeze.setOrderId(orderId);
        freeze.setRefundNo(refundNo);
        freeze.setStatus(FREEZE_ACTIVE);
        freeze.setActiveMarker(ACTIVE_MARKER);
        freeze.setPreviousOrderStatus(order.getStatus());
        freeze.setReason("REFUND_PENDING_REVIEW");
        freeze.setCreatedAtMs(now);
        requireOne(freezeMapper.insert(freeze), "refund fulfillment freeze insert");
        requireOne(refundMapper.updateApprovalId(refundNo, approval.getId()), "refund approval link update");

        return new RefundSubmissionResult(RefundSubmissionResult.Status.CREATED, orderId, refundNo,
            approval.getId(), RefundApplicationStatus.PENDING_REVIEW, eligibility.refundableAmount(),
            eligibility.riskLevel(), eligibility.policyVersion(), eligibility.reasonCodes(),
            eligibility.ruleIds(), "退款申请已创建，履约已冻结，等待人工审核；不会自动打款",
            idempotencyKey);
    }

    private RefundEligibilityResult eligibilityFor(String orderId, OrderDO order) {
        RefundEligibilityResult result = policyEvaluator.evaluate(order.getStatus(), order.getAmount());
        if (result.decision() == RefundEligibilityResult.Decision.ELIGIBLE
            && refundMapper.countEffectiveRefunds(orderId, TYPE_REFUND) > 0) {
            return policyEvaluator.existingRefundRequiresReview();
        }
        return result;
    }

    private UserPrincipal currentIdentity() {
        try {
            return UserIdentityContext.require();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private boolean owns(UserPrincipal principal, OrderDO order) {
        return principal.tenantId().equals(order.getTenantId())
            && principal.userId().equals(order.getUserId());
    }

    private RefundPreparationResult preparationFromEligibility(String orderId, OrderDO order,
                                                               RefundEligibilityResult eligibility) {
        RefundPreparationResult.Status status = eligibility.decision()
            == RefundEligibilityResult.Decision.INELIGIBLE
            ? RefundPreparationResult.Status.INELIGIBLE : RefundPreparationResult.Status.HUMAN_REVIEW;
        return new RefundPreparationResult(status, orderId, order.getProductName(),
            eligibility.refundableAmount(), null, eligibility.riskLevel(), eligibility.policyVersion(),
            eligibility.reasonCodes(), eligibility.ruleIds(), null, 0L, true, true);
    }

    private RefundPreparationResult preparationReview(String orderId, String code) {
        return new RefundPreparationResult(RefundPreparationResult.Status.HUMAN_REVIEW, orderId, null,
            null, null, RefundEligibilityResult.RiskLevel.NORMAL, RefundPolicyEvaluator.POLICY_VERSION,
            List.of(code), List.of(), null, 0L, true, true);
    }

    private RefundPreparationResult preparationHighRisk(String orderId, OrderDO order) {
        return new RefundPreparationResult(RefundPreparationResult.Status.HUMAN_REVIEW, orderId,
            order.getProductName(), null, null, RefundEligibilityResult.RiskLevel.HIGH,
            RefundPolicyEvaluator.POLICY_VERSION, List.of("REFUND_HIGH_RISK_REASON"),
            List.of(RefundRiskEvaluator.UNTRUSTED_REASON_RULE_ID), null, 0L, true, true);
    }

    private RefundPreparationResult preparationIneligible(String orderId, String code) {
        return new RefundPreparationResult(RefundPreparationResult.Status.INELIGIBLE, orderId, null,
            null, null, RefundEligibilityResult.RiskLevel.NORMAL, RefundPolicyEvaluator.POLICY_VERSION,
            List.of(code), List.of(), null, 0L, false, true);
    }

    private RefundSubmissionResult submissionFromEligibility(String orderId,
                                                             RefundEligibilityResult eligibility) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.HUMAN_REVIEW, orderId,
            null, null, null, eligibility.refundableAmount(), eligibility.riskLevel(),
            eligibility.policyVersion(), eligibility.reasonCodes(), eligibility.ruleIds(),
            "退款申请需要人工审核，当前未创建申请");
    }

    private RefundSubmissionResult submissionHighRisk(String orderId) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.HUMAN_REVIEW, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.HIGH,
            RefundPolicyEvaluator.POLICY_VERSION, List.of("REFUND_HIGH_RISK_REASON"),
            List.of(RefundRiskEvaluator.UNTRUSTED_REASON_RULE_ID),
            "原因说明触发高风险人工复核，未创建退款申请");
    }

    private RefundSubmissionResult submissionReview(String orderId, String code) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.HUMAN_REVIEW, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, List.of(code), List.of(),
            "退款申请未创建，已转人工审核");
    }

    private RefundSubmissionResult submissionNotFound(String orderId, String idempotencyKey) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.NOT_FOUND, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, List.of("REFUND_APPLICATION_NOT_FOUND"), List.of(),
            "未查询到该订单的活跃退款申请，可以使用同一幂等键安全重试", idempotencyKey);
    }

    private RefundSubmissionResult submissionUnknown(String orderId, String idempotencyKey, String code) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.UNKNOWN, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, List.of(code), List.of(),
            "退款申请结果暂时无法确认，未自动重复提交，已转人工处理", idempotencyKey);
    }

    private RefundSubmissionResult submissionFailed(String orderId, String code) {
        return new RefundSubmissionResult(RefundSubmissionResult.Status.FAILED, orderId,
            null, null, null, null, RefundEligibilityResult.RiskLevel.NORMAL,
            RefundPolicyEvaluator.POLICY_VERSION, List.of(code), List.of(),
            "退款申请系统暂时不可用，已转人工审核");
    }

    private RefundSubmissionResult existingResult(RefundDO existing, String orderId, String idempotencyKey) {
        RefundApplicationStatus status = parseApplicationStatus(existing.getApplicationStatus());
        return new RefundSubmissionResult(RefundSubmissionResult.Status.EXISTING, orderId,
            existing.getRefundNo(), existing.getApprovalId(), status, existing.getAmount(),
            parseRisk(existing.getRiskLevel()), existing.getPolicyVersion(), List.of(),
            parseRuleIds(existing.getRuleIdsJson()),
            "已存在退款申请，未重复创建", idempotencyKey);
    }

    private boolean isPendingReview(RefundDO refund) {
        return APPLICATION_PENDING_REVIEW.equals(refund.getApplicationStatus());
    }

    private boolean isRejected(RefundDO refund) {
        return RefundApplicationStatus.REJECTED.name().equals(refund.getApplicationStatus())
            || "DENIED".equals(refund.getStatus());
    }

    private boolean isApprovalConflict(IllegalStateException error) {
        String message = error.getMessage();
        return message != null && message.contains("approval already decided");
    }

    private RefundApplicationStatus parseApplicationStatus(String value) {
        try {
            return RefundApplicationStatus.valueOf(value);
        } catch (Exception e) {
            return RefundApplicationStatus.PENDING_REVIEW;
        }
    }

    private static List<String> parseRuleIds(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        String content = json.trim();
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1);
        }
        if (content.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(content.split(","))
            .map(String::trim)
            .map(value -> value.replaceAll("^\"|\"$", ""))
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static String safeIdempotencyKey(UserPrincipal principal, String orderId) {
        if (principal == null || principal.tenantId() == null || principal.tenantId().isBlank()
            || orderId == null || orderId.isBlank()) {
            return null;
        }
        try {
            return RefundIdempotencyKey.fullRefund(principal.tenantId(), orderId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private RefundEligibilityResult.RiskLevel parseRisk(String value) {
        try {
            return RefundEligibilityResult.RiskLevel.valueOf(value);
        } catch (Exception e) {
            return RefundEligibilityResult.RiskLevel.NORMAL;
        }
    }

    private static void requireOne(int affectedRows, String operation) {
        if (affectedRows != 1) {
            throw new IllegalStateException(operation + " affected rows=" + affectedRows);
        }
    }

    private static String toJson(List<String> values) {
        return values == null ? "[]" : values.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** 按订单查询最近一笔退款工单进度。 */
    private String doQueryRefundProgress(String orderId) {
        try {
            String status = refundMapper.queryLatestRefundStatus(orderId, TYPE_REFUND);
            if (status == null) {
                return "未查询到订单 " + orderId + " 的退款记录，请确认是否已提交退款申请。";
            }
            if (STATUS_APPROVED.equals(status)) {
                return "订单 " + orderId + " 的退款进度：人工审核已通过，等待支付渠道执行，尚未确认到账。";
            }
            return "订单 " + orderId + " 的退款进度：待人工复核，预计 1 个工作日内处理完成。";
        } catch (Exception e) {
            log.error("refund progress query failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-PROGRESS-FAIL", orderId, e);
            return "退款进度查询暂时不可用，建议稍后再试。";
        }
    }

    /** 提交退货工单（cw_refund 同表 type=RETURN）。 */
    private String doSubmitReturn(String orderId, String reason) {
        String refundNo = TYPE_RETURN.substring(0, 2) + System.currentTimeMillis();
        boolean ok = insertRefund(refundNo, orderId, TYPE_RETURN, STATUS_PENDING,
            null, reason, null, "AFTERSALES-BACKEND-RETURN-FAIL");
        if (!ok) {
            return "退货工单系统暂时不可用，已为您转接人工坐席。";
        }
        return "已生成退货工单 " + refundNo + "：订单=" + orderId + "，原因=" + reason
            + "。请在 7 天内将商品寄回，回寄地址与运费规则将以短信发送。";
    }

    /** 提交换货工单（cw_refund 同表 type=EXCHANGE，new_spec 记录目标规格）。 */
    private String doSubmitExchange(String orderId, String reason, String newSpec) {
        String refundNo = TYPE_EXCHANGE.substring(0, 2) + System.currentTimeMillis();
        boolean ok = insertRefund(refundNo, orderId, TYPE_EXCHANGE, STATUS_PENDING,
            null, reason, newSpec, "AFTERSALES-BACKEND-EXCHANGE-FAIL");
        if (!ok) {
            return "换货工单系统暂时不可用，已为您转接人工坐席。";
        }
        return "已生成换货工单 " + refundNo + "：订单=" + orderId
            + "，换为「" + newSpec + "」，原因=" + reason + "。新规格有货，收到回寄商品后为您发出。";
    }

    /** 价保校验：联查 cw_order 下单金额，比对当前是否降价（只读，不打款）。 */
    private String doCheckPriceProtection(String orderId) {
        try {
            OrderDO order = orderMapper.selectById(orderId);
            if (order == null) {
                return "未查询到订单 " + orderId + "，无法办理价保，请核对订单号。";
            }
            String paid = order.getAmount().toPlainString();
            return "订单 " + orderId + " 价保校验：在价保有效期内，当前未监测到降价，暂无可补差价"
                 + "（下单金额 " + paid + " 元）；若后续降价可再次发起价保申请。";
        } catch (Exception e) {
            log.error("price protection check failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-PRICEPROTECT-FAIL", orderId, e);
            return "价保系统暂时不可用，建议稍后再试。";
        }
    }

    /** 发票申请：真实落库到 cw_invoice_request（PENDING）。 */
    private String doRequestInvoice(String orderId, String invoiceTitle) {
        try {
            InvoiceRequestDO record = new InvoiceRequestDO();
            record.setOrderId(orderId);
            record.setInvoiceTitle(invoiceTitle);
            record.setStatus(STATUS_PENDING);
            record.setCreatedAtMs(System.currentTimeMillis());
            invoiceRequestMapper.insert(record);
            return "已受理订单 " + orderId + " 的发票申请，抬头=「" + invoiceTitle + "」，"
                + "电子发票将于 24 小时内发送至您账户预留邮箱。";
        } catch (Exception e) {
            log.error("invoice request failed, code={}, orderId={}",
                "AFTERSALES-BACKEND-INVOICE-FAIL", orderId, e);
            return "发票系统暂时不可用，建议稍后再试。";
        }
    }

    /** 插入一条售后工单（退款/退货/换货共表），返回是否成功。 */
    private boolean insertRefund(String refundNo, String orderId, String type, String status,
                                 BigDecimal amount, String reason, String newSpec, String errorCode) {
        try {
            RefundDO record = new RefundDO();
            record.setRefundNo(refundNo);
            record.setOrderId(orderId);
            record.setType(type);
            record.setStatus(status);
            record.setAmount(amount);
            record.setReason(reason);
            record.setNewSpec(newSpec);
            record.setCreatedAtMs(System.currentTimeMillis());
            refundMapper.insert(record);
            return true;
        } catch (Exception e) {
            log.error("aftersales ticket insert failed, code={}, orderId={}, type={}",
                errorCode, orderId, type, e);
            return false;
        }
    }

    /** String 金额转 BigDecimal，非数值返回 null（不阻断工单生成）。 */
    private static BigDecimal toDecimal(String amount) {
        if (amount == null || amount.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(amount.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
