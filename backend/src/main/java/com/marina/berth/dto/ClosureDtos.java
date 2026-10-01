package com.marina.berth.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 模块六/七：封航令与返港处置 —— 入参。 */
public final class ClosureDtos {

    private ClosureDtos() {
    }

    /** 发布封航令（effectiveAt 到点自动生效） */
    public record ClosureCreateReq(
            @NotBlank(message = "封航事由不能为空") String reasonType,
            @Size(max = 256, message = "事由说明最多 256 字") String reasonDetail,
            @NotNull(message = "发布生效时间不能为空") LocalDateTime effectiveAt,
            @Size(max = 32, message = "发布人最多 32 字") String issuedBy) {
    }

    /** 手动提前生效（主管在时间未到时立即宣布封航） */
    public record ActivateReq(String operator) {
    }

    /** 发起解除封航（进入解除中） / 归档 */
    public record OperatorReq(String operator) {
    }

    /** 记一次联系（同一 requestId 重复提交幂等，只落一条） */
    public record ContactReq(
            String contactAt,
            @NotBlank(message = "联系结果不能为空") String resultCode,
            @Size(max = 128, message = "结果描述最多 128 字") String resultText,
            @Size(max = 256, message = "备注最多 256 字") String remark,
            String operator,
            String requestId) {
    }

    /** 失联升级（带失联说明；也可由「失联上报」类联系自动触发） */
    public record EscalateReq(
            @Size(max = 256, message = "失联说明最多 256 字") String reason,
            String operator,
            String requestId) {
    }

    /** 为处置项预留可接纳返港的泊位 */
    public record AssignBerthReq(
            @NotNull(message = "请选择预留泊位") Long berthId,
            String operator) {
    }

    /** 登记安全靠泊：处置项 + 出航申报 + 船只 + 靠泊记录 + 泊位 一笔事务，整笔回退 */
    public record SafeBerthReq(
            Long berthId,
            String berthTime,
            @Size(max = 256, message = "备注最多 256 字") String remark,
            String operator,
            String requestId) {
    }
}
