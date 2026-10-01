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
 * 封航令对出航申报的影响台账：暂停放行 / 新建受阻 / 解除后恢复各一条流水。
 *
 * <p>这是只追加的历史表：申报后来被批准或取消，这些行也不删不改，
 * 用于历史封航令追溯「当时哪些申报被影响、按什么顺序被暂停」。
 */
@Getter
@Setter
@Entity
@Table(name = "closure_plan_effect")
public class ClosurePlanEffect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "closure_order_id", nullable = false)
    private NavigationClosureOrder closureOrder;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "sailing_plan_id", nullable = false)
    private SailingPlan sailingPlan;

    /** SUSPENDED 暂停放行 / BLOCKED_NEW 新建待批受阻 / RESTORED 解除后恢复可审批 */
    @Column(name = "effect_type", nullable = false, length = 24)
    private String effectType;

    /** 发生影响时申报状态快照 */
    @Column(name = "plan_status_snap", nullable = false, length = 16)
    private String planStatusSnap;

    /** 暂停时分配的原审批顺序 */
    @Column(name = "hold_seq")
    private Integer holdSeq;

    /** 说明（暂停原因等） */
    @Column(name = "remark", length = 256)
    private String remark;

    /** 操作人 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
