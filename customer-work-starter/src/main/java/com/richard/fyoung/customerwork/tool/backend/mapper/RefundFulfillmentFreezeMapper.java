package com.richard.fyoung.customerwork.tool.backend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richard.fyoung.customerwork.tool.backend.entity.RefundFulfillmentFreezeDO;
import org.apache.ibatis.annotations.Param;

/** 退款履约冻结 Mapper；ACTIVE 唯一键由数据库保证同订单只有一条。 */
public interface RefundFulfillmentFreezeMapper extends BaseMapper<RefundFulfillmentFreezeDO> {

    RefundFulfillmentFreezeDO findActiveByOrderId(@Param("orderId") String orderId);

    RefundFulfillmentFreezeDO findActiveByRefundNo(@Param("refundNo") String refundNo);

    int release(@Param("freezeId") String freezeId, @Param("releasedAtMs") long releasedAtMs);
}
