package com.marina.berth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 模块一：泊位 —— 入参。 */
public final class BerthDtos {

    private BerthDtos() {
    }

    /** 新建 / 编辑泊位 */
    public record BerthSaveReq(
            @NotBlank(message = "泊位号不能为空") String berthNo,
            @NotBlank(message = "所属区域不能为空") String zoneCode,
            String pierNo,
            @NotNull(message = "泊位长度不能为空") @DecimalMin(value = "0.01", message = "泊位长度必须大于 0") BigDecimal lengthM,
            @NotNull(message = "最大吃水不能为空") @DecimalMin(value = "0.01", message = "最大吃水必须大于 0") BigDecimal maxDraftM,
            String allowType,
            @NotNull(message = "日租金不能为空") @DecimalMin(value = "0.01", message = "日租金必须大于 0") BigDecimal dailyRate,
            String utility,
            String status) {
    }

    /** 安排靠泊：船只 + 预计离泊时间 */
    public record OccupyReq(
            @NotNull(message = "请选择要安排的船只") Long vesselId,
            LocalDateTime etaDepart) {
    }

    /** 办理离泊：可传入指定离泊时间（不传则按当前时间） */
    public record DepartReq(LocalDateTime actualDepart) {
    }

    /** 泊位状态变更：空闲 / 预留 / 停用维修 */
    public record BerthStatusReq(@NotBlank(message = "目标状态不能为空") String status) {
    }
}
