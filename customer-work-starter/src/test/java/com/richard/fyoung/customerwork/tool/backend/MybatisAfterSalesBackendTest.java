package com.richard.fyoung.customerwork.tool.backend;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customerwork.capability.approval.MybatisApprovalStore;
import com.richard.fyoung.customerwork.capability.approval.ApprovalStatus;
import com.richard.fyoung.customerwork.capability.approval.ExecutionStatus;
import com.richard.fyoung.customerwork.capability.approval.PendingApprovalService;
import com.richard.fyoung.customerwork.capability.approval.entity.ApprovalRequestDO;
import com.richard.fyoung.customerwork.capability.approval.mapper.ApprovalMapper;
import com.richard.fyoung.customerwork.core.support.MybatisTestSupport;
import com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor;
import com.richard.fyoung.customerwork.safety.security.UserIdentityContext;
import com.richard.fyoung.customerwork.safety.security.UserPrincipal;
import com.richard.fyoung.customerwork.tool.backend.entity.InvoiceRequestDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundFulfillmentFreezeDO;
import com.richard.fyoung.customerwork.tool.backend.mapper.InvoiceRequestMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.OrderMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundMapper;
import com.richard.fyoung.customerwork.tool.backend.mapper.RefundFulfillmentFreezeMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MyBatis 售后后端测试（对接本机 MySQL；不可达自动跳过）：退款资格联查 cw_order、工单落库往返、种子进度查询。
 *
 * <p>写操作用例统一使用随机订单号，finally 删除自建的 cw_refund / cw_invoice_request 行，绝不污染种子数据。</p>
 * @author owlzhangfq@gmail.com
 */
class MybatisAfterSalesBackendTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;

    private HikariDataSource dataSource;
    private RefundMapper refundMapper;
    private InvoiceRequestMapper invoiceRequestMapper;
    private RefundFulfillmentFreezeMapper freezeMapper;
    private ApprovalMapper approvalMapper;
    private MybatisAfterSalesBackend backend;

    @BeforeEach
    void setUp() {
        assumeTrue(reachable(HOST, PORT), "MySQL 不可达（" + HOST + ":" + PORT + "），跳过该测试");
        dataSource = MybatisTestSupport.mysqlDataSource("test-aftersales-pool");
        MybatisTestSupport.ensureSchema(dataSource);
        refundMapper = MybatisTestSupport.mapper(dataSource, RefundMapper.class);
        invoiceRequestMapper = MybatisTestSupport.mapper(dataSource, InvoiceRequestMapper.class);
        freezeMapper = MybatisTestSupport.mapper(dataSource, RefundFulfillmentFreezeMapper.class);
        approvalMapper = MybatisTestSupport.mapper(dataSource, ApprovalMapper.class);
        OrderMapper orderMapper = MybatisTestSupport.mapper(dataSource, OrderMapper.class);
        backend = new MybatisAfterSalesBackend(refundMapper, invoiceRequestMapper, orderMapper,
            freezeMapper, new PendingApprovalService(new MybatisApprovalStore(approvalMapper)),
            com.richard.fyoung.customerwork.infra.transaction.CustomerWorkTransactionExecutor.DIRECT,
            new RefundConfirmationTokenService("mybatis-after-sales-test-secret"));
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void checkRefundEligibility_paidNotShippedOrder_shouldBeEligible() {
        RefundEligibilityResult result = backend.checkRefundEligibility("20260613003").block();
        assertEquals(RefundEligibilityResult.Decision.ELIGIBLE, result.decision());
        assertEquals(new BigDecimal("199.00"), result.refundableAmount());
    }

    @Test
    void checkRefundEligibility_shippedOrder_shouldRequireHumanReview() {
        RefundEligibilityResult result = backend.checkRefundEligibility("20260613001").block();
        assertEquals(RefundEligibilityResult.Decision.HUMAN_REVIEW, result.decision());
        assertTrue(result.reasonCodes().contains("ORDER_ALREADY_SHIPPED"));
    }

    @Test
    void checkRefundEligibility_refundedOrder_shouldReject() {
        RefundEligibilityResult result = backend.checkRefundEligibility("20260613004").block();
        assertEquals(RefundEligibilityResult.Decision.INELIGIBLE, result.decision());
        assertTrue(result.reasonCodes().contains("ORDER_ALREADY_REFUNDED"));
    }

    @Test
    void checkRefundEligibility_unknownOrder_shouldReturnNotFound() {
        RefundEligibilityResult result = backend.checkRefundEligibility("99999999999").block();
        assertEquals(RefundEligibilityResult.Decision.INELIGIBLE, result.decision());
        assertTrue(result.reasonCodes().contains("ORDER_NOT_FOUND"));
    }

    @Test
    void submitRefund_shouldCreatePendingReviewApplicationAndFreeze() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String approvalId = null;
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "商品有瑕疵").block());
            RefundSubmissionResult result = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "商品有瑕疵",
                    prepared.confirmationToken()).block());
            approvalId = result.approvalId();
            assertEquals(RefundPreparationResult.Status.READY, prepared.status());
            assertEquals(RefundSubmissionResult.Status.CREATED, result.status());
            assertEquals(RefundApplicationStatus.PENDING_REVIEW, result.applicationStatus());
            assertEquals("REFUND:FULL:default:" + orderId, result.idempotencyKey());
            RefundSubmissionResult existing = UserIdentityContext.callWith(user,
                () -> backend.queryRefundApplication(orderId).block());
            assertEquals(RefundSubmissionResult.Status.EXISTING, existing.status());
            assertEquals(result.refundNo(), existing.refundNo());
            assertEquals(result.idempotencyKey(), existing.idempotencyKey());
            assertNotNull(freezeMapper.findActiveByOrderId(orderId));
            assertNotNull(approvalMapper.selectById(approvalId));
        } finally {
            deleteRefundByOrder(orderId);
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
        }
    }

    @Test
    void submitRefund_whenFreezeWriteFails_shouldRollbackRefundAndApproval() {
        String orderId = "20260613003";
        RefundFulfillmentFreezeMapper failingFreezeMapper = mock(RefundFulfillmentFreezeMapper.class);
        when(failingFreezeMapper.insert(any(RefundFulfillmentFreezeDO.class)))
            .thenThrow(new IllegalStateException("simulated freeze write failure"));

        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CustomerWorkTransactionExecutor transactionExecutor = action -> template.execute(status -> action.get());
        MybatisAfterSalesBackend transactionalBackend = new MybatisAfterSalesBackend(
            refundMapper, invoiceRequestMapper, MybatisTestSupport.mapper(dataSource, OrderMapper.class),
            failingFreezeMapper, new PendingApprovalService(new MybatisApprovalStore(approvalMapper)),
            transactionExecutor, new RefundConfirmationTokenService("mybatis-after-sales-rollback-secret"));
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        int approvalsBefore = approvalMapper.selectList(new LambdaQueryWrapper<ApprovalRequestDO>()
            .eq(ApprovalRequestDO::getOrderId, orderId)).size();

        RefundSubmissionResult result = UserIdentityContext.callWith(user, () -> {
            RefundPreparationResult prepared = transactionalBackend
                .prepareRefund(orderId, "OTHER", "事务回滚测试").block();
            return transactionalBackend.submitRefund(orderId, "OTHER", "事务回滚测试",
                prepared.confirmationToken()).block();
        });

        assertEquals(RefundSubmissionResult.Status.FAILED, result.status());
        assertNull(refundMapper.findActiveRefund(orderId, "REFUND"),
            "冻结写入失败时退款申请必须随事务回滚");
        assertNull(freezeMapper.findActiveByOrderId(orderId),
            "冻结写入失败时不能留下履约冻结");
        assertEquals(approvalsBefore, approvalMapper.selectList(new LambdaQueryWrapper<ApprovalRequestDO>()
            .eq(ApprovalRequestDO::getOrderId, orderId)).size(),
            "冻结写入失败时审批记录也必须随事务回滚");
    }

    @Test
    void denyRefund_whenFreezeReleaseFails_shouldRollbackDecisionApplicationAndOrder() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String refundNo = null;
        String approvalId = null;
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "拒绝事务回滚测试").block());
            RefundSubmissionResult submitted = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "拒绝事务回滚测试",
                    prepared.confirmationToken()).block());
            refundNo = submitted.refundNo();
            approvalId = submitted.approvalId();

            RefundFulfillmentFreezeMapper failingFreezeMapper = mock(RefundFulfillmentFreezeMapper.class);
            when(failingFreezeMapper.findActiveByRefundNo(any()))
                .thenAnswer(invocation -> freezeMapper.findActiveByRefundNo(invocation.getArgument(0)));
            when(failingFreezeMapper.release(any(), anyLong()))
                .thenThrow(new IllegalStateException("simulated freeze release failure"));
            TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            CustomerWorkTransactionExecutor transactionExecutor = action ->
                template.execute(status -> action.get());
            MybatisAfterSalesBackend transactionalBackend = new MybatisAfterSalesBackend(
                refundMapper, invoiceRequestMapper, MybatisTestSupport.mapper(dataSource, OrderMapper.class),
                failingFreezeMapper, new PendingApprovalService(new MybatisApprovalStore(approvalMapper)),
                transactionExecutor, new RefundConfirmationTokenService("mybatis-after-sales-rejection-secret"));

            RefundApprovalResult failed = transactionalBackend
                .denyRefund(approvalId, "reviewer-a", "证据不足").block();

            assertEquals(RefundApprovalResult.Status.FAILED, failed.status());
            RefundDO refund = refundMapper.selectById(refundNo);
            assertNotNull(refund, "拒绝事务失败时退款申请不能消失");
            assertEquals(RefundApplicationStatus.PENDING_REVIEW.name(), refund.getApplicationStatus());
            assertEquals("ACTIVE", refund.getActiveMarker());
            assertEquals("PENDING", approvalMapper.selectById(approvalId).getStatus());
            assertNotNull(freezeMapper.findActiveByRefundNo(refundNo),
                "释放冻结失败时原冻结必须保留");
            assertEquals("待发货", MybatisTestSupport.mapper(dataSource, OrderMapper.class)
                .selectById(orderId).getStatus());
        } finally {
            if (refundNo != null) {
                refundMapper.deleteById(refundNo);
            }
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
        }
    }

    @Test
    void queryRefundProgress_seededApprovedRefund_shouldReportRefunded() {
        String result = backend.queryRefundProgress("20260613004").block();
        assertTrue(result.contains("人工审核已通过") && result.contains("等待支付渠道执行")
            && result.contains("尚未确认到账"), "种子 APPROVED_PENDING_EXECUTION 不得报成已到账");
    }

    @Test
    void queryRefundProgress_noRefundRecord_shouldReturnNotFound() {
        String result = backend.queryRefundProgress("99999999999").block();
        assertTrue(result.contains("未查询到订单") && result.contains("退款记录"));
    }

    @Test
    void queryRefundProgressStatus_seededApprovedRefund_shouldSeparateLifecycleStates() {
        RefundProgressResult result = backend.queryRefundProgressStatus("20260613004").block();

        assertEquals(RefundProgressResult.QueryStatus.FOUND, result.queryStatus());
        assertEquals(RefundApplicationStatus.APPROVED_PENDING_EXECUTION, result.applicationStatus());
        assertEquals(ExecutionStatus.NOT_APPLICABLE, result.executionStatus());
        assertTrue(result.message().contains("分开"));
    }

    @Test
    void denyRefund_shouldRejectReleaseFreezeRestoreOrder_andRejectDuplicateDecision() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String refundNo = null;
        String approvalId = null;
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "拒绝恢复测试").block());
            RefundSubmissionResult submitted = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "拒绝恢复测试",
                    prepared.confirmationToken()).block());
            refundNo = submitted.refundNo();
            approvalId = submitted.approvalId();

            RefundApprovalResult denied = backend.denyRefund(approvalId, "reviewer-a", "证据不足").block();
            RefundApprovalResult duplicate = backend.denyRefund(approvalId, "reviewer-b", "重复决策").block();
            RefundProgressResult progress = backend.queryRefundProgressStatus(orderId).block();

            assertEquals(RefundApprovalResult.Status.REJECTED, denied.status());
            assertEquals(RefundApplicationStatus.REJECTED, denied.applicationStatus());
            assertEquals(ApprovalStatus.DENIED, denied.approvalStatus());
            assertEquals(RefundApprovalResult.Status.CONFLICT, duplicate.status());
            assertNull(freezeMapper.findActiveByOrderId(orderId));
            assertEquals("待发货", MybatisTestSupport.mapper(dataSource, OrderMapper.class)
                .selectById(orderId).getStatus());
            assertEquals(RefundApplicationStatus.REJECTED, progress.applicationStatus());
            assertEquals(ApprovalStatus.DENIED, progress.approvalStatus());
        } finally {
            if (refundNo != null) {
                refundMapper.deleteById(refundNo);
            }
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
        }
    }

    @Test
    void approveRefund_shouldMoveToPendingExecution_withoutExecutingFunds() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String refundNo = null;
        String approvalId = null;
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "批准状态测试").block());
            RefundSubmissionResult submitted = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "批准状态测试",
                    prepared.confirmationToken()).block());
            refundNo = submitted.refundNo();
            approvalId = submitted.approvalId();

            RefundApprovalResult approved = backend.approveRefund(approvalId, "reviewer-a", "证据充分").block();
            RefundApprovalResult duplicate = backend.approveRefund(approvalId, "reviewer-b", "重复决策").block();

            assertEquals(RefundApprovalResult.Status.APPROVED, approved.status());
            assertEquals(RefundApplicationStatus.APPROVED_PENDING_EXECUTION, approved.applicationStatus());
            assertEquals(ApprovalStatus.APPROVED, approved.approvalStatus());
            assertEquals(ExecutionStatus.NOT_APPLICABLE, approved.executionStatus());
            assertEquals(RefundApprovalResult.Status.CONFLICT, duplicate.status());
            assertNotNull(freezeMapper.findActiveByOrderId(orderId));
        } finally {
            if (refundNo != null) {
                refundMapper.deleteById(refundNo);
            }
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
        }
    }

    @Test
    void executeRefund_shouldUseOriginalIdempotencyMarkOrderRefunded_andBeIdempotent() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String refundNo = null;
        String approvalId = null;
        AtomicInteger gatewayCalls = new AtomicInteger();
        RefundPaymentGateway gateway = request -> {
            gatewayCalls.incrementAndGet();
            assertEquals("REFUND:FULL:default:" + orderId, request.idempotencyKey());
            return new RefundPaymentGateway.RefundPaymentResult(
                RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED, "PAY-MYSQL-1", "渠道已受理");
        };
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "资金执行测试").block());
            RefundSubmissionResult submitted = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "资金执行测试",
                    prepared.confirmationToken()).block());
            refundNo = submitted.refundNo();
            approvalId = submitted.approvalId();
            backend.approveRefund(approvalId, "reviewer-a", "通过").block();

            MybatisAfterSalesBackend executionBackend = backendWithGateway(gateway);
            RefundExecutionResult executed = executionBackend.executeRefund(approvalId).block();
            RefundExecutionResult duplicate = executionBackend.executeRefund(approvalId).block();
            RefundProgressResult progress = executionBackend.queryRefundProgressStatus(orderId).block();

            assertEquals(RefundExecutionResult.Status.EXECUTED, executed.status());
            assertEquals("PAY-MYSQL-1", executed.executionReference());
            assertEquals(RefundExecutionResult.Status.EXECUTED, duplicate.status());
            assertEquals(1, gatewayCalls.get(), "重复执行不得重复调用支付渠道");
            assertEquals(RefundApplicationStatus.EXECUTED, progress.applicationStatus());
            assertEquals(ExecutionStatus.EXECUTED, progress.executionStatus());
            assertEquals("已退款", MybatisTestSupport.mapper(dataSource, OrderMapper.class)
                .selectById(orderId).getStatus());
            assertNull(freezeMapper.findActiveByRefundNo(refundNo));
            assertEquals("PAY-MYSQL-1", refundMapper.selectById(refundNo).getExecutionReference());
        } finally {
            if (refundNo != null) {
                refundMapper.deleteById(refundNo);
            }
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
            MybatisTestSupport.mapper(dataSource, OrderMapper.class).updateStatus(orderId, "待发货");
        }
    }

    @Test
    void retryRefundExecution_unknownResult_shouldQueryBeforeReplayingOriginalRequest() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String refundNo = null;
        String approvalId = null;
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        RefundPaymentGateway gateway = new RefundPaymentGateway() {
            @Override
            public RefundPaymentResult execute(RefundPaymentRequest request) {
                if (executions.incrementAndGet() == 1) {
                    return new RefundPaymentResult(RefundPaymentResult.Status.UNKNOWN, null, "渠道超时");
                }
                return new RefundPaymentResult(RefundPaymentResult.Status.ACCEPTED, "PAY-MYSQL-2", "渠道已受理");
            }

            @Override
            public RefundPaymentResult query(String idempotencyKey) {
                queries.incrementAndGet();
                return new RefundPaymentResult(RefundPaymentResult.Status.NOT_FOUND, null, "渠道无记录");
            }
        };
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "渠道超时恢复测试").block());
            RefundSubmissionResult submitted = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "渠道超时恢复测试",
                    prepared.confirmationToken()).block());
            refundNo = submitted.refundNo();
            approvalId = submitted.approvalId();
            backend.approveRefund(approvalId, "reviewer-a", "通过").block();
            MybatisAfterSalesBackend executionBackend = backendWithGateway(gateway);

            RefundExecutionResult unknown = executionBackend.executeRefund(approvalId).block();
            RefundExecutionResult recovered = executionBackend.retryRefundExecution(approvalId).block();

            assertEquals(RefundExecutionResult.Status.UNKNOWN, unknown.status());
            assertEquals(RefundExecutionResult.Status.EXECUTED, recovered.status());
            assertEquals(1, queries.get(), "未知结果恢复必须先查询渠道");
            assertEquals(2, executions.get());
            assertEquals(RefundApplicationStatus.EXECUTED,
                executionBackend.queryRefundProgressStatus(orderId).block().applicationStatus());
        } finally {
            if (refundNo != null) {
                refundMapper.deleteById(refundNo);
            }
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
            MybatisTestSupport.mapper(dataSource, OrderMapper.class).updateStatus(orderId, "待发货");
        }
    }

    @Test
    void executeRefund_shouldRejectUnapprovedApplication() {
        String orderId = "20260613003";
        UserPrincipal user = new UserPrincipal("U-demo-2", "demo", "Demo", "default");
        String refundNo = null;
        String approvalId = null;
        try {
            RefundPreparationResult prepared = UserIdentityContext.callWith(user,
                () -> backend.prepareRefund(orderId, "OTHER", "未审批执行测试").block());
            RefundSubmissionResult submitted = UserIdentityContext.callWith(user,
                () -> backend.submitRefund(orderId, "OTHER", "未审批执行测试",
                    prepared.confirmationToken()).block());
            refundNo = submitted.refundNo();
            String createdApprovalId = submitted.approvalId();
            approvalId = createdApprovalId;

            RefundExecutionResult result = backendWithGateway(request ->
                new RefundPaymentGateway.RefundPaymentResult(
                    RefundPaymentGateway.RefundPaymentResult.Status.ACCEPTED, "PAY-NO", "不应调用"))
                .executeRefund(createdApprovalId).block();

            assertEquals(RefundExecutionResult.Status.CONFLICT, result.status());
            assertEquals(RefundApplicationStatus.PENDING_REVIEW,
                backend.queryRefundProgressStatus(orderId).block().applicationStatus());
        } finally {
            if (refundNo != null) {
                refundMapper.deleteById(refundNo);
            }
            freezeMapper.delete(new LambdaQueryWrapper<RefundFulfillmentFreezeDO>()
                .eq(RefundFulfillmentFreezeDO::getOrderId, orderId));
            if (approvalId != null) {
                approvalMapper.deleteById(approvalId);
            }
        }
    }

    @Test
    void submitReturn_shouldGenerateReturnTicket() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String result = backend.submitReturn(orderId, "不喜欢了").block();
            assertTrue(result.contains("已生成退货工单"), "应生成退货工单");
            assertTrue(result.contains("7 天内将商品寄回"));
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void submitExchange_shouldGenerateExchangeTicket() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String result = backend.submitExchange(orderId, "尺寸不合适", "白色/大号").block();
            assertTrue(result.contains("已生成换货工单"), "应生成换货工单");
            assertTrue(result.contains("换为「白色/大号」"));
        } finally {
            deleteRefundByOrder(orderId);
        }
    }

    @Test
    void checkPriceProtection_seededOrder_shouldReadOrderAmount() {
        String result = backend.checkPriceProtection("20260613001").block();
        assertTrue(result.contains("价保校验"));
        assertTrue(result.contains("下单金额 299.00 元"), "应联查 cw_order 金额");
    }

    @Test
    void checkPriceProtection_unknownOrder_shouldReturnNotFound() {
        String result = backend.checkPriceProtection("99999999999").block();
        assertTrue(result.contains("未查询到订单"));
    }

    @Test
    void requestInvoice_shouldPersistRequest() {
        String orderId = "T-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String result = backend.requestInvoice(orderId, "北京示例科技有限公司").block();
            assertTrue(result.contains("已受理"), "应受理发票申请");
            assertTrue(result.contains("抬头=「北京示例科技有限公司」"));
        } finally {
            deleteInvoiceByOrder(orderId);
        }
    }

    private void deleteRefundByOrder(String orderId) {
        refundMapper.delete(new LambdaQueryWrapper<RefundDO>().eq(RefundDO::getOrderId, orderId));
    }

    private void deleteInvoiceByOrder(String orderId) {
        invoiceRequestMapper.delete(new LambdaQueryWrapper<InvoiceRequestDO>().eq(InvoiceRequestDO::getOrderId, orderId));
    }

    private MybatisAfterSalesBackend backendWithGateway(RefundPaymentGateway gateway) {
        return new MybatisAfterSalesBackend(refundMapper, invoiceRequestMapper,
            MybatisTestSupport.mapper(dataSource, OrderMapper.class), freezeMapper,
            new PendingApprovalService(new MybatisApprovalStore(approvalMapper)),
            CustomerWorkTransactionExecutor.DIRECT,
            new RefundConfirmationTokenService("mybatis-after-sales-test-secret"), gateway);
    }
}
