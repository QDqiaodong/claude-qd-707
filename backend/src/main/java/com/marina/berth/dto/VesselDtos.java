package com.marina.berth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 模块二：船只 —— 入参。 */
public final class VesselDtos {

    private VesselDtos() {
    }

    /** 新建 / 编辑船只 */
    public record VesselSaveReq(
            @NotBlank(message = "船名不能为空") String name,
            @NotBlank(message = "船籍注册号不能为空") String regNo,
            @NotBlank(message = "船主不能为空") String ownerName,
            @NotBlank(message = "船型不能为空") String typeCode,
            @NotNull(message = "船长不能为空") @DecimalMin(value = "0.01", message = "船长必须大于 0") BigDecimal lengthM,
            @NotNull(message = "船宽不能为空") @DecimalMin(value = "0.01", message = "船宽必须大于 0") BigDecimal widthM,
            @NotNull(message = "吃水不能为空") @DecimalMin(value = "0.01", message = "吃水必须大于 0") BigDecimal draftM,
            LocalDate regDate,
            LocalDate insuranceDue,
            String status) {
    }

    /** 状态变更：在港 / 出海 / 维修 / 退租 */
    public record VesselStatusReq(@NotBlank(message = "目标状态不能为空") String status) {
    }

    /** 续保：把保险到期日往后延（传新到期日） */
    public record InsuranceReq(@NotNull(message = "新的保险到期日不能为空") LocalDate insuranceDue) {
    }
}
