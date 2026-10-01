package com.marina.berth.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 模块六：封航与返港处置 —— 入参。 */
public final class ClosureDtos {

    private ClosureDtos() {
    }

    /** 发布封航令（effectiveAt 可填未来时间 → 待生效；不填按立即生效） */
    public record ClosureCreateReq(
            @NotBlank(message = "封航事由类型不能为空") String reasonType,
            @NotBlank(message = "封航标题不能为空") @Size(max = 128, message = "标题最长 128 字") String title,
            @NotBlank(message = "详细说明不能为空") @Size(max = 512, message = "详细说明最长 512 字") String description,
            String issuedBy,
            LocalDateTime effectiveAt) {
    }

    /** 手动激活待生效封航令 */
    public record ActivateReq(String operator) {
    }

    /** 申请解除（进入解除中） */
    public record ReleaseReq(
            String operator,
            @Size(max = 256, message = "解除备注最长 256 字") String remark) {
    }

    /** 归档（风险项清零后才允许） */
    public record ArchiveReq(String operator) {
    }

    /**
     * 处置项联系操作（确认返港 / 失联升级 / 重新取得联系 / 普通联系备注）。
     * clientRequestId 由前端每次点击生成，重复点击/超时重试带同一个令牌 → 服务端幂等。
     */
    public record ContactReq(
            @NotBlank(message = "联系结果不能为空") String result,
            String remark,
            String operator,
            String clientRequestId,
            LocalDateTime contactTime,
            Long plannedBerthId) {
    }

    /** 安全靠泊：关联申报、船只、靠泊记录、泊位一次性一致提交，失败整笔回退 */
    public record SafeBerthReq(
            @NotNull(message = "请选择接纳返港船只的泊位") Long berthId,
            String operator,
            String clientRequestId,
            String remark,
            LocalDateTime berthTime) {
    }
}
