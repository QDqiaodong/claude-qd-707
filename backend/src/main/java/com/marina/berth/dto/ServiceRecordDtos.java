package com.marina.berth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 模块五：码头服务与费用 —— 入参。 */
public final class ServiceRecordDtos {

    private ServiceRecordDtos() {
    }

    /** 新建服务记录：金额由服务端按 单价 × 数量 现算，不接受前端传 */
    public record ServiceSaveReq(
            @NotBlank(message = "服务类型不能为空") String serviceType,
            @NotNull(message = "单价不能为空") @DecimalMin(value = "0.01", message = "单价必须大于 0") BigDecimal unitPrice,
            @NotNull(message = "数量不能为空") @DecimalMin(value = "0.01", message = "数量必须大于 0") BigDecimal quantity,
            LocalDate occurredDate,
            Long berthId,
            Long vesselId,
            String settleStatus) {
    }

    /** 结算状态变更：已结 / 挂账 / 退回未结 */
    public record SettleReq(@NotBlank(message = "结算状态不能为空") String settleStatus) {
    }
}
