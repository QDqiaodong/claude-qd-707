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
 * 封航令：SCHEDULED 待生效 → EFFECTIVE 生效中 → LIFTING 解除中 → ARCHIVED 已归档。
 *
 * <p>状态机只允许沿一个方向流转（{@code com.marina.berth.service.ClosureService} 内做守卫），
 * 生效时点以 {@link #effectiveAt} 为准：到点由定时任务（或接口懒触发）在单事务内
 * 完成「暂停已批准未出航申报 + 为每条在海船生成返港处置项」的快照。
 */
@Getter
@Setter
@Entity
@Table(name = "closure_order")
public class ClosureOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 封航令编号，如 FH-202610-0001 */
    @Column(name = "order_no", nullable = false, length = 24)
    private String orderNo;

    /** TYPHOON 台风 / MARITIME_CONTROL 海事管制 / WATER_RISK 突发水域风险 / OTHER 其他 */
    @Column(name = "reason_type", nullable = false, length = 16)
    private String reasonType;

    /** 事由说明 */
    @Column(name = "reason_detail", length = 256)
    private String reasonDetail;

    /** 发布生效时间（到点自动生效，可晚于创建时间） */
    @Column(name = "effective_at", nullable = false)
    private LocalDateTime effectiveAt;

    /** SCHEDULED / EFFECTIVE / LIFTING / ARCHIVED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 发布值班主管 */
    @Column(name = "issued_by", length = 32)
    private String issuedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 实际生效时间 */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /** 进入解除中的时间 */
    @Column(name = "lifting_started_at")
    private LocalDateTime liftingStartedAt;

    @Column(name = "lifted_by", length = 32)
    private String liftedBy;

    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "archived_by", length = 32)
    private String archivedBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
