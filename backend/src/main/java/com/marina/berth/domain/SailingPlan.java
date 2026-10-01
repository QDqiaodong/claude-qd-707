package com.marina.berth.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

/** 出航申报（待审批 → 已批准 → 已返航 / 已取消）。 */
@Data
@Entity
@Table(name = "sailing_plan")
public class SailingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 申报单号，如 SP-2026-0001 */
    @Column(name = "plan_no", nullable = false, length = 24)
    private String planNo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    /** 船长 / 驾驶员 */
    @Column(name = "skipper", nullable = false, length = 32)
    private String skipper;

    /** 联系电话 */
    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    /** 出航时间 */
    @Column(name = "depart_time", nullable = false)
    private LocalDateTime departTime;

    /** 预计返回时间 */
    @Column(name = "eta_return", nullable = false)
    private LocalDateTime etaReturn;

    /** 目的地 / 航线 */
    @Column(name = "route", nullable = false, length = 128)
    private String route;

    /** 载员人数 */
    @Column(name = "crew_count", nullable = false)
    private Integer crewCount;

    /** 海况天气预报 */
    @Column(name = "weather", nullable = false, length = 128)
    private String weather;

    /** PENDING 待审批 / APPROVED 已批准 / SUSPENDED 暂停放行 / RETURNED 已返航 / CANCELED 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 暂停它的封航令 id；解除归档后置空（暂停原因与顺序列保留留痕） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hold_by_order_id")
    private NavigationClosureOrder holdByOrder;

    /** 暂停时的原审批顺序：解除归档后按此顺序恢复为可继续审批 */
    @Column(name = "hold_seq")
    private Integer holdSeq;

    /** 暂停原因（封航令编号 + 事由），解除后也保留，可追溯为什么被拦过 */
    @Column(name = "hold_reason", length = 256)
    private String holdReason;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
