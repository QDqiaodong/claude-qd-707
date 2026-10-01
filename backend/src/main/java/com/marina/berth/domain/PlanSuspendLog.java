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
 * 申报暂停/恢复审计日志：封航生效时批量写入 SUSPEND（带暂停原因），解除封航进入解除中时
 * 批量写入 RESUME。原审批顺序由 sailing_plan.approved_at 保留，恢复后申报回到「已批准，
 * 可继续审批（放行）」队列而不是被自动批准。
 */
@Getter
@Setter
@Entity
@Table(name = "plan_suspend_log")
public class PlanSuspendLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "closure_id", nullable = false)
    private ClosureOrder closure;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private SailingPlan plan;

    /** SUSPEND 暂停放行 / RESUME 恢复可审批 */
    @Column(name = "action", nullable = false, length = 16)
    private String action;

    @Column(name = "reason", length = 256)
    private String reason;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
