package com.marina.berth.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 模块四：出航申报 —— 入参。 */
public final class SailingPlanDtos {

    private SailingPlanDtos() {
    }

    /** 新建出航申报 */
    public record PlanSaveReq(
            @NotBlank(message = "申报单号不能为空") String planNo,
            @NotNull(message = "请选择船只") Long vesselId,
            @NotBlank(message = "船长/驾驶员不能为空") String skipper,
            @NotBlank(message = "联系电话不能为空")
            @Pattern(regexp = "^1[3-9]\\d{9}$", message = "联系电话必须是 11 位手机号") String phone,
            @NotNull(message = "出航时间不能为空") LocalDateTime departTime,
            @NotNull(message = "预计返回时间不能为空") LocalDateTime etaReturn,
            @NotBlank(message = "目的地/航线不能为空") String route,
            @NotNull(message = "载员人数不能为空")
            @Min(value = 1, message = "载员至少 1 人")
            @Max(value = 12, message = "载员最多 12 人") Integer crewCount,
            @NotBlank(message = "海况天气预报不能为空") String weather) {
    }
}
