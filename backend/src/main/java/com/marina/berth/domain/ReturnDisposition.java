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
import lombok.Getter;
import lombok.Setter;

/**
 * 返港处置项：封航生效快照时，为每条「封航前已出海」的船生成且仅生成一条
 * （表上有 uk_disp_closure_vessel(closure_id, vessel_id) 兜底并发）。
 *
 * <p>状态机：TO_CONTACT 待联系 → CONFIRMED_RETURN 已确认返港 / ESCALATED 失联升级
 * → SAFE_BERTHED 已安全靠泊（终态，不可回退）。CONFIRMED_RETURN 与 ESCALATED 之间
 * 允许随最新一次联系互转（失联船重新联系上可再确认返港），但任何状态都不允许退回 TO_CONTACT。
 */
@Getter
@Setter
@Entity
@Table(name = "return_disposition")
public class ReturnDisposition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "closure_id", nullable = false)
    private ClosureOrder closure;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    /** 封航前出海对应的已批准申报（可空，例如船只状态为出海但无对应申报） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sailing_plan_id")
    private SailingPlan sailingPlan;

    /** TO_CONTACT / CONFIRMED_RETURN / ESCALATED / SAFE_BERTHED */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 预先接纳返港的预留泊位（RESERVED 状态，靠泊/释放时清空） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_berth_id")
    private Berth assignedBerth;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "assigned_by", length = 32)
    private String assignedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "escalate_reason", length = 256)
    private String escalateReason;

    @Column(name = "berthed_at")
    private LocalDateTime berthedAt;

    @Column(name = "berthed_berth_id")
    private Long berthedBerthId;

    @Column(name = "berthed_by", length = 32)
    private String berthedBy;

    /** 安全靠泊时生成的在泊靠泊记录 id */
    @Column(name = "berthing_id")
    private Long berthingId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
