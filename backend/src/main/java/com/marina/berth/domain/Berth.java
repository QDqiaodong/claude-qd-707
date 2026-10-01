package com.marina.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JoinFormula;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 泊位。
 *
 * <p>本仓的数据访问主路线 —— Hibernate {@code @JoinFormula} 派生关联：
 * {@code currentVessel}（当前在泊船只）在 {@code berth} 表上【没有任何列】，
 * 它的值由一条子查询现算出来：从 berthing 表里挑出该泊位最新的一条「在泊」记录，
 * 取其 vessel_id 作为外键。因此它天然只读，不参与任何写入（insert / update）与脏检查。
 *
 * <p>公式里未加表前缀的 {@code id} 指的就是当前泊位行的主键，Hibernate 会自动替换成别名。
 * 配 {@code fetch = LAZY}：列表查询时只多带一个子查询列，不额外 join。
 *
 * <p>用 @Getter/@Setter 而不是 @Data：避免 lombok 生成的 toString / equals
 * 碰到派生关联去触发懒加载，甚至与 Vessel 的反向派生互撞。
 */
@Getter
@Setter
@Entity
@Table(name = "berth")
public class Berth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 泊位号，如 A-01 */
    @Column(name = "berth_no", nullable = false, length = 16)
    private String berthNo;

    /** 所属区域：A / B / C */
    @Column(name = "zone_code", nullable = false, length = 4)
    private String zoneCode;

    /** 栈桥编号，如 A1 / B2 */
    @Column(name = "pier_no", nullable = false, length = 16)
    private String pierNo;

    /** 泊位长度（m） */
    @Column(name = "length_m", nullable = false, precision = 6, scale = 2)
    private BigDecimal lengthM;

    /** 最大吃水（m） */
    @Column(name = "max_draft_m", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDraftM;

    /** 允许船型，分号分隔，如 SAIL;FISHING */
    @Column(name = "allow_type", nullable = false, length = 64)
    private String allowType;

    /** 日租金（元/天） */
    @Column(name = "daily_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal dailyRate;

    /** 水电接口：POWER_WATER 水电齐全 / POWER 仅供电 / WATER 仅供水 / NONE 无 */
    @Column(name = "utility", nullable = false, length = 16)
    private String utility;

    /** FREE 空闲 / IN_PORT 在泊 / RESERVED 预留 / DISABLED 停用维修 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 为哪条封航令预留的返港泊位（RESERVED 时非空，靠泊/释放时清空） */
    @Column(name = "reserved_return_id")
    private Long reservedReturnId;

    /**
     * 【@JoinFormula 派生关联】当前在泊船只：取该泊位最新一条「在泊」靠泊记录的船只。
     * 只读派生列，库表里没有对应字段。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinFormula("(SELECT b.vessel_id FROM berthing b WHERE b.berth_id = id "
            + "AND b.status = 'IN_PORT' ORDER BY b.berth_time DESC LIMIT 1)")
    private Vessel currentVessel;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
