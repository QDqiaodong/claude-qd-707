package com.marina.berth.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：这些对象只在 service 里构造、直接序列化给前端，不需要 setter。
 * record 的访问器（如 {@code berthNo()}）Jackson 原生支持。
 *
 * <p>其中带 {@code current*} 前缀的字段（泊位上的当前船只、船上的当前泊位）来自
 * 实体上的 {@code @JoinFormula} 派生关联，不是冗余列。
 */
public final class Views {

    private Views() {
    }

    /** 泊位（currentVessel* 来自 Berth.currentVessel 的 @JoinFormula 派生关联） */
    public record BerthView(
            Long id,
            String berthNo,
            String zoneCode,
            String pierNo,
            BigDecimal lengthM,
            BigDecimal maxDraftM,
            String allowType,
            String allowTypeNames,
            BigDecimal dailyRate,
            String utility,
            String utilityName,
            String status,
            String statusName,
            Long currentVesselId,
            String currentVesselName,
            String currentVesselType,
            String currentVesselTypeName,
            String currentVesselOwner,
            BigDecimal currentVesselLengthM,
            BigDecimal currentVesselDraftM,
            Long currentBerthingId,
            String currentBerthingNo,
            LocalDateTime currentBerthTime,
            LocalDateTime currentEtaDepart,
            LocalDateTime updatedAt) {
    }

    /** 船只台账（currentBerthNo 来自 Vessel.currentBerth 的 @JoinFormula 派生关联） */
    public record VesselView(
            Long id,
            String name,
            String regNo,
            String ownerName,
            String typeCode,
            String typeName,
            BigDecimal lengthM,
            BigDecimal widthM,
            BigDecimal draftM,
            LocalDate regDate,
            LocalDate insuranceDue,
            Long insuranceDays,
            String insuranceLevel,
            String status,
            String statusName,
            Long currentBerthId,
            String currentBerthNo,
            Integer berthingCount,
            BigDecimal berthingFee,
            LocalDateTime updatedAt) {
    }

    /** 靠泊记录 */
    public record BerthingView(
            Long id,
            String recordNo,
            Long berthId,
            String berthNo,
            Long vesselId,
            String vesselName,
            LocalDateTime berthTime,
            LocalDateTime etaDepart,
            LocalDateTime actualDepart,
            Integer days,
            BigDecimal fee,
            String status,
            String statusName,
            LocalDateTime updatedAt) {
    }

    /** 出航申报 */
    public record SailingPlanView(
            Long id,
            String planNo,
            Long vesselId,
            String vesselName,
            String vesselType,
            String vesselTypeName,
            String berthNo,
            String skipper,
            String phone,
            LocalDateTime departTime,
            LocalDateTime etaReturn,
            String route,
            Integer crewCount,
            String weather,
            String status,
            String statusName,
            Long overdueHours,
            LocalDateTime updatedAt) {
    }

    /** 码头服务与费用记录 */
    public record ServiceRecordView(
            Long id,
            String serviceType,
            String serviceTypeName,
            String serviceUnit,
            BigDecimal unitPrice,
            BigDecimal quantity,
            BigDecimal amount,
            LocalDate occurredDate,
            String month,
            Long berthId,
            String berthNo,
            Long vesselId,
            String vesselName,
            String settleStatus,
            String settleStatusName,
            LocalDateTime updatedAt) {
    }

    /** 服务费月度汇总里的一项 */
    public record MonthSum(String month, BigDecimal amount, Long count, BigDecimal settledAmount) {
    }

    /** 服务费按类型汇总里的一项 */
    public record TypeSum(String serviceType, String serviceTypeName, BigDecimal amount, Long count) {
    }

    /** 服务费汇总：总额 + 未结 + 按月 + 按类型 */
    public record ServiceSummary(
            BigDecimal totalAmount,
            BigDecimal unsettledAmount,
            BigDecimal onAccountAmount,
            Long totalCount,
            List<MonthSum> byMonth,
            List<TypeSum> byType) {
    }

    /** 码头总览计数器 */
    public record Overview(
            Long berthTotal,
            Long berthFree,
            Long berthInPort,
            Long berthReserved,
            Long berthDisabled,
            Long vesselTotal,
            Long vesselInPort,
            Long vesselAtSea,
            Long vesselMaintenance,
            Long planPending,
            Long planApproved,
            Long planOverdue,
            Long serviceUnsettled,
            BigDecimal serviceUnsettledAmount,
            BigDecimal monthServiceAmount,
            Long insuranceWarn) {
    }
}
