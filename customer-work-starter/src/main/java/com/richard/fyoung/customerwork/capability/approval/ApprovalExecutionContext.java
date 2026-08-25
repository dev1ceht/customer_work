package com.richard.fyoung.customerwork.capability.approval;

/**
 * 审批下游执行上下文。
 *
 * @param request 审批单快照
 * @param idempotencyKey 下游必须按此键去重；通用审批默认为 approvalId，业务执行器可以绑定原业务幂等键
 * @param fencingToken 本次执行租约令牌；旧租约不能覆盖新租约结果
 */
public record ApprovalExecutionContext(ApprovalRequest request, String idempotencyKey, String fencingToken) {
}
