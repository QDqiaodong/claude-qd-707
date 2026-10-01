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
 * 返港处置联系记录：每次联系的时间、结果、备注都留一条，安全靠泊也补一条 BERTHED。
 *
 * <p>{@code clientRequestId} 是前端给每次操作生成的幂等令牌，库表唯一键
 * {@code (disposition_id, client_request_id)} 保证：两名值班员同时确认、重复点击、
 * 网络超时重试都只会落一条记录、状态只推进一次。
 */
@Getter
@Setter
@Entity
@Table(name = "disposition_contact")
public class DispositionContact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "disposition_id", nullable = false)
    private ReturnDisposition disposition;

    /** 联系时间 */
    @Column(name = "contact_time", nullable = false)
    private LocalDateTime contactTime;

    /** CONTACTED / CONFIRMED_RETURN / LOST_CONTACT / REACHED / BERTHED */
    @Column(name = "result", nullable = false, length = 24)
    private String result;

    /** 备注 */
    @Column(name = "remark", length = 256)
    private String remark;

    /** 值班员 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    /** 前端幂等令牌（同一处置项内唯一） */
    @Column(name = "client_request_id", length = 64)
    private String clientRequestId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
