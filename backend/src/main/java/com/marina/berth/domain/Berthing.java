package com.marina.berth.domain;

import java.math.BigDecimal;
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

/**
 * 靠泊记录（写操作主实体：安排靠泊 / 办理离泊都在这里落库）。
 *
 * <p>注意：{@link Berth#getCurrentVessel()} 与 {@link Vessel#getCurrentBerth()} 两条
 * {@code @JoinFormula} 派生关联读的就是这张表的「在泊」行 —— 也就是说，泊位上的
 * 「当前船只」不是冗余字段，而是这张表的实时投影：本表一改，泊位视图立刻跟着变。
 */
@Data
@Entity
@Table(name = "berthing")
public class Berthing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 记录单号，如 KP-2026-0001 */
    @Column(name = "record_no", nullable = false, length = 24)
    private String recordNo;

    @ManyToOne(optional = false)
    @JoinColumn(name = "berth_id", nullable = false)
    private Berth berth;

    @ManyToOne(optional = false)
    @JoinColumn(name = "vessel_id", nullable = false)
    private Vessel vessel;

    /** 靠泊时间 */
    @Column(name = "berth_time", nullable = false)
    private LocalDateTime berthTime;

    /** 预计离泊时间 */
    @Column(name = "eta_depart")
    private LocalDateTime etaDepart;

    /** 实际离泊时间 */
    @Column(name = "actual_depart")
    private LocalDateTime actualDepart;

    /** 停泊天数（离泊结算时算出，不足一天按一天） */
    @Column(name = "days")
    private Integer days;

    /** 停泊费用 = 天数 × 泊位日租金 */
    @Column(name = "fee", precision = 12, scale = 2)
    private BigDecimal fee;

    /** IN_PORT 在泊 / SETTLED 已结算离泊 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
