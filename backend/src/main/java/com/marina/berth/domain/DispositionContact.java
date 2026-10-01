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
 * 返港处置联系记录：每次联系都保留时间、结果和备注，是「返港处置过程可追溯」的事实来源。
 *
 * <p>{@code request_id} 为客户端幂等键（同一按钮重复点击 / 网络超时重试携带同一值），
 * 表上 uk_contact_request 唯一约束保证一条联系只落一次库。
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
    @Column(name = "contact_at", nullable = false)
    private LocalDateTime contactAt;

    /** NOTIFIED 已通知 / ACK 确认返港 / NO_ANSWER 无人应答 / ESCALATE 失联上报 / SAFE 安全靠泊 / OTHER 其他 */
    @Column(name = "result_code", nullable = false, length = 16)
    private String resultCode;

    /** 联系结果描述 */
    @Column(name = "result_text", length = 128)
    private String resultText;

    /** 备注 */
    @Column(name = "remark", length = 256)
    private String remark;

    /** 联系值班员 */
    @Column(name = "operator", length = 32)
    private String operator;

    /** 幂等键：重复提交同一 requestId 只落一条 */
    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
