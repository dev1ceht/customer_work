package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 售后工单持久化对象（贫血数据袋，退款/退货/换货共表 {@code cw_refund}）。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("cw_refund")
public class RefundDO {

    /** 售后工单号（应用赋值，非自增）。 */
    @TableId(value = "refund_no", type = IdType.INPUT)
    private String refundNo;

    /** 关联订单号。 */
    private String orderId;

    /** 类型：REFUND/RETURN/EXCHANGE。 */
    private String type;

    /** 兼容旧进度查询的状态：PENDING/APPROVED/DENIED。 */
    private String status;

    /** 退款申请状态：PENDING_REVIEW/REJECTED/APPROVED_PENDING_EXECUTION 等。 */
    private String applicationStatus;

    /** 非空时占用订单全额退款唯一键；REJECTED 申请置空。 */
    private String activeMarker;

    /** 发起申请的已验签用户主体。 */
    private String userId;

    /** 人工审批单号。 */
    private String approvalId;

    /** 服务端按租户/订单推导的全额退款幂等键。 */
    private String idempotencyKey;

    /** 退款金额（退货/换货可空）。 */
    private BigDecimal amount;

    /** 诉求原因。 */
    private String reason;

    /** 换货目标规格。 */
    private String newSpec;

    /** 创建时间戳（毫秒）。 */
    private long createdAtMs;

    /** 资格决策使用的政策版本。 */
    private String policyVersion;

    /** 资格风险等级。 */
    private String riskLevel;

    /** 规则证据 JSON；仅用于审计回放，不由 Agent 提供。 */
    private String ruleIdsJson;

    /** 支付渠道返回的受理/执行凭证；未明确受理时为空。 */
    private String executionReference;

    /** 执行失败或结果未知的内部原因；不直接暴露给 Agent 作为业务事实。 */
    private String executionFailureReason;

    /** 支付渠道明确受理的时间戳。 */
    private long executedAtMs;
}
