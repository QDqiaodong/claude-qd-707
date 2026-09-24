package com.marina.berth.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    /** PENDING 待审批 / APPROVED 已批准 / RETURNED 已返航 / CANCELED 已取消 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
