package com.marina.berth.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 封航令。
 *
 * <p>四阶段状态机（只允许顺向流转，每一步都在 service 里加行锁）：
 * <pre>
 *   PENDING 待生效 ──到点/手动激活──▶ EFFECTIVE 生效中
 *                                        │ 申请解除（风险项未清零）
 *                                        ▼
 *                                  RELEASING 解除中
 *                                        │ 失联升级 / 确认未靠泊的处置项全部清零后归档
 *                                        ▼
 *                                  ARCHIVED 已归档（终态）
 * </pre>
 * 生效中若风险项已经清零，允许 EFFECTIVE 直接归档；ARCHIVED 不可再改。
 *
 * <p>{@code activeGuard} 映射库表里的生成列：未归档恒为 1、归档为 NULL，
 * 配合唯一索引保证同一时刻只有一条未归档封航令（并发发布由数据库兜底）。
 */
@Getter
@Setter
@Entity
@Table(name = "navigation_closure_order")
public class NavigationClosureOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 封航令编号，如 FH-202610-0001 */
    @Column(name = "order_no", nullable = false, length = 24)
    private String orderNo;

    /** TYPHOON 台风 / MARITIME_CONTROL 海事管制 / WATER_RISK 突发水域风险 */
    @Column(name = "reason_type", nullable = false, length = 16)
    private String reasonType;

    /** 封航事由标题 */
    @Column(name = "title", nullable = false, length = 128)
    private String title;

    /** 详细说明 */
    @Column(name = "description", nullable = false, length = 512)
    private String description;

    /** 发布人（值班主管） */
    @Column(name = "issued_by", nullable = false, length = 32)
    private String issuedBy;

    /** PENDING / EFFECTIVE / RELEASING / ARCHIVED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 发布生效时间：到点才真正生效 */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    /** 实际生效时间 */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /** 申请解除时间 */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    /** 归档时间 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    /** 归档操作人 */
    @Column(name = "archived_by", length = 32)
    private String archivedBy;

    /** 解除备注 */
    @Column(name = "release_remark", length = 256)
    private String releaseRemark;

    /** 生成列：未归档 = 1，已归档 = NULL。只读，不参与写入 */
    @Column(name = "active_guard", insertable = false, updatable = false)
    private Integer activeGuard;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
