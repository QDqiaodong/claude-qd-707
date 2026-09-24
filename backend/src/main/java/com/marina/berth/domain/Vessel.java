package com.marina.berth.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * 船只台账。
 *
 * <p>与 {@link Berth} 对偶的另一条 {@code @JoinFormula} 派生关联：{@code currentBerth}
 * （当前所在泊位）由 berthing 表里该船最新一条「在泊」记录反查得出，库表无此列、只读。
 */
@Getter
@Setter
@Entity
@Table(name = "vessel")
public class Vessel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 船名，如 海风号 */
    @Column(name = "name", nullable = false, length = 32)
    private String name;

    /** 船籍注册号，如 CN-YC-1001 */
    @Column(name = "reg_no", nullable = false, length = 24)
    private String regNo;

    /** 船主 */
    @Column(name = "owner_name", nullable = false, length = 32)
    private String ownerName;

    /** 船型：SAIL 帆船 / POWER 动力艇 / FISHING 钓鱼艇 / CATAMARAN 双体船 / SPEED 快艇 */
    @Column(name = "type_code", nullable = false, length = 16)
    private String typeCode;

    /** 船长（m） */
    @Column(name = "length_m", nullable = false, precision = 6, scale = 2)
    private BigDecimal lengthM;

    /** 船宽（m） */
    @Column(name = "width_m", nullable = false, precision = 5, scale = 2)
    private BigDecimal widthM;

    /** 吃水（m） */
    @Column(name = "draft_m", nullable = false, precision = 5, scale = 2)
    private BigDecimal draftM;

    /** 登记日期 */
    @Column(name = "reg_date", nullable = false)
    private LocalDate regDate;

    /** 保险到期日 */
    @Column(name = "insurance_due", nullable = false)
    private LocalDate insuranceDue;

    /** IN_PORT 在港 / OUT_SEA 出海 / MAINTENANCE 维修 / TERMINATED 退租 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /**
     * 【@JoinFormula 派生关联】当前所在泊位：取该船最新一条「在泊」靠泊记录的泊位。
     * 只读派生列，库表里没有对应字段。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinFormula("(SELECT b.berth_id FROM berthing b WHERE b.vessel_id = id "
            + "AND b.status = 'IN_PORT' ORDER BY b.berth_time DESC LIMIT 1)")
    private Berth currentBerth;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
