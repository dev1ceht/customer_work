---
name: refund-handling
description: 退款咨询与用户确认后创建待人工审核申请（starter 单测用安全样例）
---

# 仅退款与确认申请

用户咨询退款时：先调用 `queryOrder(orderId)` 核对支付与履约事实，再调用
`checkRefundEligibility(orderId)` 读取 Backend 计算的资格、全部可退余额和规则证据。
用户表达申请意图后收集标准原因码和说明，调用
`prepareRefund(orderId, reasonCode, reasonText)` 展示商品、全额可退金额、履约冻结和人工审批提示，
只有用户明确确认后才调用
`submitRefund(orderId, reasonCode, reasonText, confirmationToken)`。
未支付引导取消，已发货、部分退款、匿名/越权和结果不确定时转人工；任何结果都不得宣称自动打款或已经到账。
