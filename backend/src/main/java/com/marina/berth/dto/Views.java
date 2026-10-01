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
            Long holdByOrderId,
            String holdOrderNo,
            Integer holdSeq,
            String holdReason,
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
            Long insuranceWarn,
            Long closureActive) {
    }

    // ======================== 模块六：封航与返港 ========================

    /** 封航令列表项（带处置项计数，列表上直接看风险是否清零） */
    public record ClosureOrderView(
            Long id,
            String orderNo,
            String reasonType,
            String reasonTypeName,
            String title,
            String description,
            String issuedBy,
            String status,
            String statusName,
            LocalDateTime effectiveAt,
            LocalDateTime activatedAt,
            LocalDateTime releasedAt,
            LocalDateTime archivedAt,
            String archivedBy,
            String releaseRemark,
            Long dispositionTotal,
            Long riskCount,
            Long toContactCount,
            Long confirmedCount,
            Long lostCount,
            Long berthedCount,
            Long suspendedPlanCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 受影响出航申报（处置台「受影响申报」页签，读影响台账 + 申报实时状态） */
    public record AffectedPlanView(
            Long effectId,
            String effectType,
            String effectTypeName,
            String planStatusSnap,
            Integer holdSeq,
            String effectRemark,
            LocalDateTime effectCreatedAt,
            String effectOperator,
            Long planId,
            String planNo,
            Long vesselId,
            String vesselName,
            String vesselTypeName,
            String planStatus,
            String planStatusName,
            LocalDateTime departTime,
            LocalDateTime etaReturn,
            String skipper,
            String phone,
            String route,
            Long overdueHours,
            Integer currentHoldSeq,
            String currentHoldReason) {
    }

    /** 返港处置项（含每次联系记录） */
    public record DispositionView(
            Long id,
            Long closureOrderId,
            Long vesselId,
            String vesselName,
            String vesselType,
            String vesselTypeName,
            Long planId,
            String planNo,
            String status,
            String statusName,
            boolean risk,
            Long plannedBerthId,
            String plannedBerthNo,
            LocalDateTime contactedAt,
            LocalDateTime confirmedAt,
            LocalDateTime escalatedAt,
            LocalDateTime berthedAt,
            Long berthId,
            String berthNo,
            Long berthingId,
            String berthingNo,
            LocalDateTime createdAt,
            List<ContactView> contacts) {
    }

    /** 单次联系记录 */
    public record ContactView(
            Long id,
            LocalDateTime contactTime,
            String result,
            String resultName,
            String remark,
            String operator,
            boolean idempotentReplay,
            LocalDateTime createdAt) {
    }

    /** 可接纳返港船只的泊位（空闲、适泊信息一并带出，给靠泊下拉用） */
    public record ReturnBerthView(
            Long id,
            String berthNo,
            String zoneCode,
            BigDecimal lengthM,
            BigDecimal maxDraftM,
            String allowTypeNames,
            BigDecimal dailyRate,
            String status,
            String statusName) {
    }

    /** 封航操作日志 */
    public record ClosureLogView(
            Long id,
            String action,
            String detail,
            String operator,
            LocalDateTime createdAt) {
    }

    /** 封航处置台详情：封航令 + 受影响申报 + 在海船只处置项 + 可接纳泊位 + 日志 */
    public record ClosureDetailView(
            ClosureOrderView order,
            List<AffectedPlanView> affectedPlans,
            List<DispositionView> dispositions,
            List<ReturnBerthView> returnBerths,
            List<ClosureLogView> logs) {
    }

    /**
     * 安全靠泊结果。整笔事务成功才返回 data；任何一步失败整笔回退，
     * changedItems 明确告诉值班员哪些数据被改了，失败时由全局异常文案说明「以下数据均未改变」。
     */
    public record SafeBerthResult(
            Long dispositionId,
            String dispositionStatus,
            String dispositionStatusName,
            Long vesselId,
            String vesselName,
            String vesselStatus,
            String vesselStatusName,
            Long planId,
            String planNo,
            String planStatus,
            String planStatusName,
            Long berthingId,
            String berthingNo,
            Long berthId,
            String berthNo,
            String berthStatus,
            String berthStatusName,
            LocalDateTime berthedAt,
            List<String> changedItems,
            List<ContactView> contacts) {
    }
}
