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
 * 封航返港处置项：封航前已经出海的船，在封航令生效时自动生成一条。
 *
 * <p>状态只许前进不许倒退（{@code com.marina.berth.service.ClosureService} 里集中校验）：
 * <pre>
 *   TO_CONTACT 待联系 ──▶ CONFIRMED_RETURN 已确认返港 ──▶ BERTHED 已安全靠泊(终态)
 *        │                     ▲
 *        └──▶ LOST_CONTACT 失联升级 ──┘（重新取得联系可回到 CONFIRMED_RETURN）
 *
 *   TO_CONTACT ──▶ LOST_CONTACT（联系不上直接升级）
 *   任何非终态 ──▶ BERTHED（安全靠泊走专门的整笔事务接口）
 * </pre>
 *
 * <p>同一艘船在同一条封航令下只能有一条：库表唯一键
 * {@code (closure_order_id, vessel_id)} 兜底，并发双击由数据库拒绝第二条。
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
    @JoinColumn(name = "closure_order_id", nullable = false)
    private NavigationClosureOrder closureOrder;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    /** 对应的已批准出航申报；无申报出海（历史数据）时为空 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sailing_plan_id")
    private SailingPlan sailingPlan;

    /** TO_CONTACT / CONFIRMED_RETURN / LOST_CONTACT / BERTHED */
    @Column(name = "status", nullable = false, length = 24)
    private String status;

    /** 确认返港时预约的接纳泊位 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "planned_berth_id")
    private Berth plannedBerth;

    /** 首次联系上的时间 */
    @Column(name = "contacted_at")
    private LocalDateTime contactedAt;

    /** 确认返港时间 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 失联升级时间 */
    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    /** 安全靠泊时间 */
    @Column(name = "berthed_at")
    private LocalDateTime berthedAt;

    /** 实际靠泊泊位 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "berth_id")
    private Berth berth;

    /** 靠泊记录 id（与靠泊事务同生共死） */
    @Column(name = "berthing_id")
    private Long berthingId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
