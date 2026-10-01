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
 * 封航操作日志：发布 / 到点生效 / 暂停申报 / 申请解除 / 归档 / 处置项状态流转。
 * 只追加，不修改 —— 页面刷新、关页重开后值班主管看到的处置过程完全一致、可回放。
 */
@Getter
@Setter
@Entity
@Table(name = "closure_log")
public class ClosureLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "closure_order_id", nullable = false)
    private NavigationClosureOrder closureOrder;

    /** CREATE / ACTIVATE / SUSPEND_PLAN / BLOCKED_PLAN / RESTORE_PLANS / RELEASE / ARCHIVE / DISP_* */
    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "detail", nullable = false, length = 512)
    private String detail;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
