package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundDO;
import org.apache.ibatis.annotations.Param;

/**
 * 售后工单 Mapper（由 {@code CustomerWorkPersistenceConfig} 的 {@code @MapperScan} 扫描绑定，不加 {@code @Mapper}）。
 *
 * <p>工单落库走 {@link BaseMapper#insert}；最近状态和有效退款计数查询写在 XML 中。</p>
 * @author owlzhangfq@gmail.com
 */
public interface RefundMapper extends BaseMapper<RefundDO> {

    /** 查询某订单指定类型的最近一笔工单状态，无记录返回 null。 */
    String queryLatestRefundStatus(@Param("orderId") String orderId, @Param("type") String type);

    /** 统计会占用可退余额的待审或已通过退款；首期检测到即转人工，不计算部分退款。 */
    long countEffectiveRefunds(@Param("orderId") String orderId, @Param("type") String type);

    /** 查询订单指定类型的活跃全额退款申请，数据库唯一键负责并发保护。 */
    RefundDO findActiveRefund(@Param("orderId") String orderId, @Param("type") String type);

    /** 按服务端生成的业务幂等键查询活跃退款申请。 */
    RefundDO findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** 按审批单查询退款申请，包含已拒绝历史记录。 */
    RefundDO findByApprovalId(@Param("approvalId") String approvalId);

    /** 查询订单最近一笔退款申请，供进度和拒绝申诉查询使用。 */
    RefundDO findLatestRefund(@Param("orderId") String orderId, @Param("type") String type);

    /** 在人工决策事务内推进退款申请状态，并按 expectedStatus 做前置状态保护。 */
    int updateDecisionState(@Param("refundNo") String refundNo,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("applicationStatus") String applicationStatus,
                            @Param("status") String status,
                            @Param("activeMarker") String activeMarker);

    /** 推进资金执行状态；条件状态保护防止非法跳转或重复支付。 */
    int updateExecutionState(@Param("refundNo") String refundNo,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("applicationStatus") String applicationStatus,
                             @Param("status") String status,
                             @Param("activeMarker") String activeMarker,
                             @Param("executionReference") String executionReference,
                             @Param("executionFailureReason") String executionFailureReason,
                             @Param("executedAtMs") long executedAtMs);

    /** 在退款申请事务内回填审批单号。 */
    int updateApprovalId(@Param("refundNo") String refundNo, @Param("approvalId") String approvalId);
}
