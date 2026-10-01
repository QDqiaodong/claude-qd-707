package com.marina.berth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.BerthingDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.VesselRepository;

/**
 * 模块三：靠泊记录。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>一个泊位只放一条「在泊」记录；一条在泊记录对应一艘船、一个泊位</li>
 *   <li>适泊校验：船长 ≤ 泊位长、吃水 ≤ 最大吃水、船型在允许船型内</li>
 *   <li>停用维修的泊位不安排靠泊；维修中 / 已退租的船不安排靠泊</li>
 *   <li>一船不能同时在两个泊位</li>
 *   <li>离泊结算：天数按小时向上取整（不足一天按一天），费用 = 天数 × 泊位日租金</li>
 *   <li>靠泊会让泊位转「在泊」、船只转「在港」；离泊后泊位转「空闲」、船只转「出海」</li>
 * </ol>
 *
 * <p>这张表同时也是 {@link Berth#getCurrentVessel()}、{@link Vessel#getCurrentBerth()}
 * 两条 {@code @JoinFormula} 派生关联的数据来源：这里的「在泊」行一改，
 * 泊位列表里的「当前船只」、船只台账里的「当前泊位」立刻跟着变，没有冗余字段要维护。
 */
@Service
public class BerthingService {

    private static final DateTimeFormatter NO_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final BerthingRepository berthingRepository;
    private final BerthRepository berthRepository;
    private final VesselRepository vesselRepository;

    public BerthingService(BerthingRepository berthingRepository,
                           BerthRepository berthRepository,
                           VesselRepository vesselRepository) {
        this.berthingRepository = berthingRepository;
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.BerthingView> list(Long berthId, Long vesselId, String status) {
        List<Berthing> source;
        if (berthId != null) {
            source = berthingRepository.findAllByBerthIdOrderByBerthTimeDesc(berthId);
        } else if (vesselId != null) {
            source = berthingRepository.findAllByVesselIdOrderByBerthTimeDesc(vesselId);
        } else if (status != null && !status.isBlank()) {
            source = berthingRepository.findAllByStatusOrderByBerthTimeDesc(status.trim().toUpperCase());
        } else {
            source = berthingRepository.findAllByOrderByBerthTimeDesc();
        }
        List<Views.BerthingView> list = new ArrayList<>();
        for (Berthing item : source) {
            list.add(toView(item));
        }
        return list;
    }

    /** 登记靠泊（泊位图上的「安排靠泊」与本接口走同一套校验） */
    @Transactional
    public Views.BerthingView create(BerthingDtos.BerthingSaveReq req) {
        return arrive(req.berthId(), req.vesselId(),
                req.berthTime() == null ? LocalDateTime.now() : req.berthTime(),
                req.etaDepart(), null);
    }

    /**
     * 安排靠泊：核心写操作。
     *
     * @param recordNo 为空时按 单号前缀 + 年月 + 序号 自动生成
     */
    @Transactional
    public Views.BerthingView arrive(Long berthId, Long vesselId, LocalDateTime berthTime,
                                     LocalDateTime etaDepart, String recordNo) {
        return arrive(berthId, vesselId, berthTime, etaDepart, recordNo, false);
    }

    /**
     * 安排靠泊（可选行锁）。
     *
     * <p>{@code lockBerth=true} 时用 {@code PESSIMISTIC_WRITE} 先锁泊位行：
     * 封航返港安全靠泊要在【同一个事务】里连续改 处置项/申报/船只/靠泊记录/泊位，
     * 两名值班员给不同船选了同一个泊位时，靠唯一键 + 普通 select 只能在撞键后报错回滚，
     * 行锁让并发事务在这一步排队，后到者拿到锁时泊位已是「在泊」，直接走业务校验失败文案。
     */
    @Transactional
    public Views.BerthingView arrive(Long berthId, Long vesselId, LocalDateTime berthTime,
                                     LocalDateTime etaDepart, String recordNo, boolean lockBerth) {
        Berth berth = lockBerth
                ? berthRepository.findByIdForUpdate(berthId)
                        .orElseThrow(() -> new BizException("泊位不存在：id=" + berthId))
                : berthRepository.findById(berthId)
                        .orElseThrow(() -> new BizException("泊位不存在：id=" + berthId));
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));

        if ("DISABLED".equals(berth.getStatus())) {
            throw new BizException("泊位 " + berth.getBerthNo() + " 处于停用维修，不能安排靠泊");
        }
        if ("IN_PORT".equals(berth.getStatus())
                || berthingRepository.findFirstByBerthIdAndStatusOrderByBerthTimeDesc(berthId, "IN_PORT").isPresent()) {
            throw new BizException("泊位 " + berth.getBerthNo() + " 已有船在泊，一个泊位只停一艘船");
        }
        if ("RESERVED".equals(berth.getStatus())) {
            throw new BizException("泊位 " + berth.getBerthNo() + " 已被预留，请先取消预留再安排靠泊");
        }
        if ("MAINTENANCE".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 正在维修，不能安排靠泊");
        }
        if ("TERMINATED".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 已退租，不能安排靠泊");
        }
        if (berthingRepository.findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vesselId, "IN_PORT").isPresent()) {
            throw new BizException("船只 " + vessel.getName() + " 已在泊，一艘船只能占一个泊位");
        }
        if (vessel.getLengthM().compareTo(berth.getLengthM()) > 0) {
            throw new BizException("船长 " + vessel.getLengthM() + "m 超过泊位 " + berth.getBerthNo()
                    + " 的长度 " + berth.getLengthM() + "m，放不下");
        }
        if (vessel.getDraftM().compareTo(berth.getMaxDraftM()) > 0) {
            throw new BizException("船只吃水 " + vessel.getDraftM() + "m 超过泊位 " + berth.getBerthNo()
                    + " 的最大吃水 " + berth.getMaxDraftM() + "m，会搁浅");
        }
        if (!MarinaDict.allowTypeContains(berth.getAllowType(), vessel.getTypeCode())) {
            throw new BizException("泊位 " + berth.getBerthNo() + " 只允许 "
                    + MarinaDict.allowTypeNames(berth.getAllowType()) + "，"
                    + MarinaDict.vesselTypeName(vessel.getTypeCode()) + " 不能停靠");
        }

        LocalDateTime berthAt = berthTime;
        if (etaDepart != null && etaDepart.isBefore(berthAt)) {
            throw new BizException("预计离泊时间不能早于靠泊时间");
        }

        Berthing record = new Berthing();
        record.setRecordNo(recordNo == null || recordNo.isBlank() ? nextRecordNo(berthAt) : recordNo.trim());
        record.setBerth(berth);
        record.setVessel(vessel);
        record.setBerthTime(berthAt);
        record.setEtaDepart(etaDepart);
        record.setActualDepart(null);
        record.setDays(null);
        record.setFee(null);
        record.setStatus("IN_PORT");
        record.setUpdatedAt(LocalDateTime.now());

        berth.setStatus("IN_PORT");
        berth.setUpdatedAt(LocalDateTime.now());
        berthRepository.save(berth);
        vessel.setStatus("IN_PORT");
        vessel.setUpdatedAt(LocalDateTime.now());
        vesselRepository.save(vessel);

        return toView(berthingRepository.save(record));
    }

    /** 办理离泊结算 */
    @Transactional
    public Views.BerthingView depart(Long berthingId, LocalDateTime actualDepart) {
        Berthing record = berthingRepository.findById(berthingId)
                .orElseThrow(() -> new BizException("靠泊记录不存在：id=" + berthingId));
        if (!"IN_PORT".equals(record.getStatus())) {
            throw new BizException("该靠泊记录已结算离泊，无需重复办理");
        }
        LocalDateTime departAt = actualDepart == null ? LocalDateTime.now() : actualDepart;
        if (departAt.isBefore(record.getBerthTime())) {
            throw new BizException("离泊时间不能早于靠泊时间");
        }
        Berth berth = record.getBerth();
        Vessel vessel = record.getVessel();

        long hours = Duration.between(record.getBerthTime(), departAt).toHours();
        int days = (int) Math.max(MarinaDict.MIN_BERTH_DAYS, Math.ceil(hours / 24.0));
        BigDecimal fee = berth.getDailyRate().multiply(BigDecimal.valueOf(days)).setScale(2, RoundingMode.HALF_UP);

        record.setActualDepart(departAt);
        record.setDays(days);
        record.setFee(fee);
        record.setStatus("SETTLED");
        record.setUpdatedAt(LocalDateTime.now());

        berth.setStatus("FREE");
        berth.setUpdatedAt(LocalDateTime.now());
        berthRepository.save(berth);
        vessel.setStatus("OUT_SEA");
        vessel.setUpdatedAt(LocalDateTime.now());
        vesselRepository.save(vessel);

        return toView(berthingRepository.save(record));
    }

    @Transactional(readOnly = true)
    public List<Views.BerthingView> listByVessel(Long vesselId) {
        List<Views.BerthingView> list = new ArrayList<>();
        for (Berthing item : berthingRepository.findAllByVesselIdOrderByBerthTimeDesc(vesselId)) {
            list.add(toView(item));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<Views.BerthingView> listByBerth(Long berthId) {
        List<Views.BerthingView> list = new ArrayList<>();
        for (Berthing item : berthingRepository.findAllByBerthIdOrderByBerthTimeDesc(berthId)) {
            list.add(toView(item));
        }
        return list;
    }

    private String nextRecordNo(LocalDateTime berthAt) {
        String prefix = "KP-" + berthAt.format(NO_SUFFIX) + "-";
        long seq = berthingRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (berthingRepository.existsByRecordNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }

    public Views.BerthingView toView(Berthing item) {
        Berth berth = item.getBerth();
        Vessel vessel = item.getVessel();
        return new Views.BerthingView(
                item.getId(),
                item.getRecordNo(),
                berth == null ? null : berth.getId(),
                berth == null ? null : berth.getBerthNo(),
                vessel == null ? null : vessel.getId(),
                vessel == null ? null : vessel.getName(),
                item.getBerthTime(),
                item.getEtaDepart(),
                item.getActualDepart(),
                item.getDays(),
                item.getFee(),
                item.getStatus(),
                MarinaDict.berthingStatusName(item.getStatus()),
                item.getUpdatedAt());
    }
}
