package com.marina.berth.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotNull;

/** 模块三：靠泊记录 —— 入参。 */
public final class BerthingDtos {

    private BerthingDtos() {
    }

    /** 直接登记一条靠泊（泊位图上「安排靠泊」走的是同一个业务入口） */
    public record BerthingSaveReq(
            @NotNull(message = "请选择泊位") Long berthId,
            @NotNull(message = "请选择船只") Long vesselId,
            LocalDateTime berthTime,
            LocalDateTime etaDepart) {
    }

    /** 办理离泊结算 */
    public record DepartReq(LocalDateTime actualDepart) {
    }
}
