package com.marina.berth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.VesselDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.VesselRepository;

/**
 * 模块二：船只台账。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>船名、船籍注册号均不可重复</li>
 *   <li>新建船只默认「出海」状态，要变成「在港」必须走泊位上的「安排靠泊」</li>
 *   <li>状态门禁：退租前必须先离泊结算；出海前不能还占着泊位；只有靠泊在港的船能转维修</li>
 *   <li>续保：新的保险到期日必须晚于今天，且不能早于原到期日</li>
 *   <li>列表带出保险剩余天数与预警级别（已逾期 / 30 天内 / 正常）</li>
 * </ol>
 *
 * <p>「当前泊位」{@code currentBerthNo} 来自 {@link Vessel#getCurrentBerth()} ——
 * 实体上的 {@code @JoinFormula} 派生关联，读的是 berthing 表里该船的在泊行。
 */
@Service
public class VesselService {

    private final VesselRepository vesselRepository;
    private final BerthingRepository berthingRepository;

    public VesselService(VesselRepository vesselRepository, BerthingRepository berthingRepository) {
        this.vesselRepository = vesselRepository;
        this.berthingRepository = berthingRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.VesselView> list() {
        return toViews(vesselRepository.findAllByOrderByRegNoAsc());
    }

    @Transactional(readOnly = true)
    public List<Views.VesselView> listByStatus(String status) {
        return toViews(vesselRepository.findAllByStatusOrderByRegNoAsc(status));
    }

    @Transactional(readOnly = true)
    public List<Views.VesselView> listByType(String typeCode) {
        return toViews(vesselRepository.findAllByTypeCodeOrderByRegNoAsc(typeCode));
    }

    @Transactional
    public Views.VesselView create(VesselDtos.VesselSaveReq req) {
        String name = trim(req.name());
        String regNo = trim(req.regNo());
        if (vesselRepository.existsByRegNo(regNo)) {
            throw new BizException("船籍注册号 " + regNo + " 已存在，不能重复登记");
        }
        if (vesselRepository.existsByName(name)) {
            throw new BizException("船名 " + name + " 已存在，不能重复登记");
        }
        String typeCode = trim(req.typeCode()).toUpperCase();
        if (!MarinaDict.isValidVesselType(typeCode)) {
            throw new BizException("船型只能是 SAIL / POWER / FISHING / CATAMARAN / SPEED，收到的是：" + req.typeCode());
        }
        if (req.lengthM() == null || req.lengthM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("船长必须大于 0");
        }
        if (req.widthM() == null || req.widthM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("船宽必须大于 0");
        }
        if (req.draftM() == null || req.draftM().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("吃水必须大于 0");
        }
        String status = req.status() == null || req.status().isBlank()
                ? "OUT_SEA"
                : req.status().trim().toUpperCase();
        if (!MarinaDict.isValidVesselStatus(status) || "IN_PORT".equals(status)) {
            throw new BizException("新建船只只能是 OUT_SEA（出海）/ MAINTENANCE（维修）/ TERMINATED（退租），"
                    + "在港请走泊位上的「安排靠泊」");
        }
        LocalDate regDate = req.regDate() == null ? LocalDate.now() : req.regDate();
        LocalDate insuranceDue = req.insuranceDue() == null ? LocalDate.now().plusYears(1) : req.insuranceDue();
        if (!insuranceDue.isAfter(LocalDate.now())) {
            throw new BizException("保险到期日必须晚于今天");
        }

        Vessel vessel = new Vessel();
        vessel.setName(name);
        vessel.setRegNo(regNo);
        vessel.setOwnerName(trim(req.ownerName()));
        vessel.setTypeCode(typeCode);
        vessel.setLengthM(req.lengthM());
        vessel.setWidthM(req.widthM());
        vessel.setDraftM(req.draftM());
        vessel.setRegDate(regDate);
        vessel.setInsuranceDue(insuranceDue);
        vessel.setStatus(status);
        vessel.setUpdatedAt(LocalDateTime.now());
        Vessel saved = vesselRepository.save(vessel);
        return toView(saved, new HashMap<>(), new HashMap<>());
    }

    /** 状态变更：在港 / 出海 / 维修 / 退租 */
    @Transactional
    public Views.VesselView changeStatus(Long vesselId, VesselDtos.VesselStatusReq req) {
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        String status = req.status() == null ? "" : req.status().trim().toUpperCase();
        if (!MarinaDict.isValidVesselStatus(status)) {
            throw new BizException("船只状态只能是 IN_PORT / OUT_SEA / MAINTENANCE / TERMINATED，收到的是：" + req.status());
        }
        boolean inPort = berthingRepository
                .findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vesselId, "IN_PORT").isPresent();
        if ("TERMINATED".equals(status) && inPort) {
            throw new BizException("船只 " + vessel.getName() + " 还占着泊位，请先办理离泊结算再退租");
        }
        if ("OUT_SEA".equals(status) && inPort) {
            throw new BizException("船只 " + vessel.getName() + " 还在泊，请先办理离泊再出海");
        }
        if ("MAINTENANCE".equals(status) && !inPort && !"IN_PORT".equals(vessel.getStatus())) {
            throw new BizException("只有靠泊在港的船只才能转入维修状态");
        }
        vessel.setStatus(status);
        vessel.setUpdatedAt(LocalDateTime.now());
        Vessel saved = vesselRepository.save(vessel);
        return toView(saved, new HashMap<>(), new HashMap<>());
    }

    /** 续保：登记新的保险到期日 */
    @Transactional
    public Views.VesselView renewInsurance(Long vesselId, VesselDtos.InsuranceReq req) {
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        LocalDate due = req.insuranceDue();
        if (due == null || !due.isAfter(LocalDate.now())) {
            throw new BizException("新的保险到期日必须晚于今天");
        }
        if (due.isBefore(vessel.getInsuranceDue())) {
            throw new BizException("续保到期日不能早于原到期日 " + vessel.getInsuranceDue());
        }
        vessel.setInsuranceDue(due);
        vessel.setUpdatedAt(LocalDateTime.now());
        Vessel saved = vesselRepository.save(vessel);
        return toView(saved, new HashMap<>(), new HashMap<>());
    }

    @Transactional(readOnly = true)
    public Views.VesselView detail(Long vesselId) {
        Vessel vessel = vesselRepository.findById(vesselId)
                .orElseThrow(() -> new BizException("船只不存在：id=" + vesselId));
        return toView(vessel, new HashMap<>(), new HashMap<>());
    }

    private List<Views.VesselView> toViews(List<Vessel> vessels) {
        List<Berthing> all = berthingRepository.findAllByOrderByBerthTimeDesc();
        Map<Long, Integer> countMap = new HashMap<>();
        Map<Long, BigDecimal> feeSum = new HashMap<>();
        for (Berthing item : all) {
            if (item.getVessel() == null) {
                continue;
            }
            Long vesselId = item.getVessel().getId();
            countMap.put(vesselId, countMap.getOrDefault(vesselId, 0) + 1);
            if (item.getFee() != null) {
                feeSum.put(vesselId, feeSum.getOrDefault(vesselId, BigDecimal.ZERO).add(item.getFee()));
            }
        }
        List<Views.VesselView> list = new ArrayList<>();
        for (Vessel vessel : vessels) {
            list.add(toView(vessel, countMap, feeSum));
        }
        return list;
    }

    public Views.VesselView toView(Vessel vessel, Map<Long, Integer> berthingCount, Map<Long, BigDecimal> feeSum) {
        Berth berth = vessel.getCurrentBerth();
        LocalDate today = LocalDate.now();
        long days = ChronoUnit.DAYS.between(today, vessel.getInsuranceDue());
        String level = days < 0 ? "EXPIRED" : (days <= MarinaDict.INSURANCE_WARN_DAYS ? "WARN" : "OK");
        BigDecimal fee = feeSum == null ? null : feeSum.get(vessel.getId());
        return new Views.VesselView(
                vessel.getId(),
                vessel.getName(),
                vessel.getRegNo(),
                vessel.getOwnerName(),
                vessel.getTypeCode(),
                MarinaDict.vesselTypeName(vessel.getTypeCode()),
                vessel.getLengthM(),
                vessel.getWidthM(),
                vessel.getDraftM(),
                vessel.getRegDate(),
                vessel.getInsuranceDue(),
                days,
                level,
                vessel.getStatus(),
                MarinaDict.vesselStatusName(vessel.getStatus()),
                berth == null ? null : berth.getId(),
                berth == null ? null : berth.getBerthNo(),
                berthingCount == null ? 0 : berthingCount.getOrDefault(vessel.getId(), 0),
                fee == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : fee,
                vessel.getUpdatedAt());
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
