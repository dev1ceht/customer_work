package com.richard.fyoung.customerwork.tool.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 退款申请创建事务中的履约冻结记录。 */
@Data
@TableName("cw_refund_fulfillment_freeze")
public class RefundFulfillmentFreezeDO {

    @TableId(value = "freeze_id", type = IdType.INPUT)
    private String freezeId;

    private String orderId;

    private String refundNo;

    private String status;

    /** ACTIVE 时占用订单唯一键，释放后置空以允许未来新的历史记录。 */
    private String activeMarker;

    /** 创建冻结前的订单状态；拒绝审批时按此状态恢复。 */
    private String previousOrderStatus;

    private String reason;

    private long createdAtMs;

    private long releasedAtMs;
}
