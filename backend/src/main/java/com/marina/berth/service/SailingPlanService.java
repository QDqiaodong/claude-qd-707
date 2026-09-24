package com.marina.berth.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.SailingPlanDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.SailingPlanRepository;
import com.marina.berth.repository.VesselRepository;

/**
 * 模块四：出航申报。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>申报单号全局唯一；出航时间必须早于预计返回时间</li>
 *   <li>载员 1~12 人；联系电话必须是 11 位手机号</li>
 *   <li>维修中、已退租的船不得申报出航</li>
 *   <li>一船同时只能有一条「已批准未返航」的申报</li>
 *   <li>批准时若该船仍占着泊位，先自动办理离泊结算，再放行出海</li>
 *   <li>返航后船只回到「在港」待重新安排泊位；取消已批准的申报同样让船回港</li>
 *   <li>超期判定：已批准且预计返回时间已过 → 记超时小时数，前端整行标红</li>
 * </ol>
 */
@Service
public class SailingPlanService {

    private static final DateTimeFormatter NO_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final SailingPlanRepository planRepository;
    private final VesselRepository vesselRepository;
    private final BerthingRepository berthingRepository;
    private final BerthingService berthingService;

    @PersistenceContext
    private EntityManager entityManager;

    public SailingPlanService(SailingPlanRepository planRepository,
                              VesselRepository vesselRepository,
                              BerthingRepository berthingRepository,
                              BerthingService berthingService) {
        this.planRepository = planRepository;
        this.vesselRepository = vesselRepository;
        this.berthingRepository = berthingRepository;
        this.berthingService = berthingService;
    }

    @Transactional(readOnly = true)
    public List<Views.SailingPlanView> list(Long vesselId, String status) {
        List<SailingPlan> source;
        if (vesselId != null) {
            source = planRepository.findAllByVesselIdOrderByDepartTimeDesc(vesselId);
        } else if (status != null && !status.isBlank()) {
            source = planRepository.findAllByStatusOrderByDepartTimeDesc(status.trim().toUpperCase());
        } else {
            source = planRepository.findAllByOrderByDepartTimeDesc();
        }
        List<Views.SailingPlanView> list = new ArrayList<>();
        for (SailingPlan plan : source) {
            list.add(toView(plan));
        }
        return list;
    }

    @Transactional
    public Views.SailingPlanView create(SailingPlanDtos.PlanSaveReq req) {
        String planNo = req.planNo().trim();
        if (planRepository.existsByPlanNo(planNo)) {
            throw new BizException("申报单号 " + planNo + " 已存在，不能重复申报");
        }
        Vessel vessel = vesselRepository.findById(req.vesselId())
                .orElseThrow(() -> new BizException("船只不存在：id=" + req.vesselId()));
        if ("MAINTENANCE".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 正在维修，不能申报出航");
        }
        if ("TERMINATED".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 已退租，不能申报出航");
        }
        if (!req.etaReturn().isAfter(req.departTime())) {
            throw new BizException("预计返回时间必须晚于出航时间");
        }
        if (req.crewCount() > MarinaDict.MAX_CREW) {
            throw new BizException("载员最多 " + MarinaDict.MAX_CREW + " 人");
        }
        for (SailingPlan other : planRepository.findAllByVesselIdOrderByDepartTimeDesc(vessel.getId())) {
            if ("APPROVED".equals(other.getStatus())) {
                throw new BizException("船只 " + vessel.getName() + " 已有批准未返航的申报 "
                        + other.getPlanNo() + "，不能重复申报");
            }
        }

        SailingPlan plan = new SailingPlan();
        plan.setPlanNo(planNo);
        plan.setVessel(vessel);
        plan.setSkipper(req.skipper().trim());
        plan.setPhone(req.phone().trim());
        plan.setDepartTime(req.departTime());
        plan.setEtaReturn(req.etaReturn());
        plan.setRoute(req.route().trim());
        plan.setCrewCount(req.crewCount());
        plan.setWeather(req.weather().trim());
        plan.setStatus("PENDING");
        plan.setUpdatedAt(LocalDateTime.now());
        return toView(planRepository.save(plan));
    }

    /** 审批通过：放行出海（仍占泊位则先自动结算离泊） */
    @Transactional
    public Views.SailingPlanView approve(Long planId) {
        SailingPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if (!"PENDING".equals(plan.getStatus())) {
            throw new BizException("只有待审批的申报可以批准，当前状态：" + MarinaDict.planStatusName(plan.getStatus()));
        }
        Vessel vessel = plan.getVessel();
        if ("MAINTENANCE".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 正在维修，不能批准出航");
        }
        if ("TERMINATED".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 已退租，不能批准出航");
        }
        // 出海前清泊位：还占着泊位的按当前时间自动结算离泊，避免泊位长期挂着已出海的船
        Berthing open = berthingRepository
                .findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vessel.getId(), "IN_PORT").orElse(null);
        if (open != null) {
            berthingService.depart(open.getId(), LocalDateTime.now());
            // 船只上的「当前泊位」是 @JoinFormula 派生列：离泊后清空持久化上下文再重读，
            // 否则视图里还挂着已经空出来的旧泊位
            entityManager.flush();
            entityManager.clear();
            plan = planRepository.findById(planId)
                    .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
            vessel = vesselRepository.findById(plan.getVessel().getId())
                    .orElseThrow(() -> new BizException("船只不存在"));
        }
        plan.setStatus("APPROVED");
        plan.setUpdatedAt(LocalDateTime.now());
        SailingPlan saved = planRepository.save(plan);
        vessel.setStatus("OUT_SEA");
        vessel.setUpdatedAt(LocalDateTime.now());
        vesselRepository.save(vessel);
        return toView(saved);
    }

    /** 确认返航 */
    @Transactional
    public Views.SailingPlanView markReturn(Long planId) {
        SailingPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if (!"APPROVED".equals(plan.getStatus())) {
            throw new BizException("只有已批准的申报可以登记返航，当前状态：" + MarinaDict.planStatusName(plan.getStatus()));
        }
        plan.setStatus("RETURNED");
        plan.setUpdatedAt(LocalDateTime.now());
        SailingPlan saved = planRepository.save(plan);
        Vessel vessel = plan.getVessel();
        vessel.setStatus("IN_PORT");
        vessel.setUpdatedAt(LocalDateTime.now());
        vesselRepository.save(vessel);
        return toView(saved);
    }

    /** 取消申报 */
    @Transactional
    public Views.SailingPlanView cancel(Long planId) {
        SailingPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if (!"PENDING".equals(plan.getStatus()) && !"APPROVED".equals(plan.getStatus())) {
            throw new BizException("已返航或已取消的申报不能再取消，当前状态：" + MarinaDict.planStatusName(plan.getStatus()));
        }
        boolean wasApproved = "APPROVED".equals(plan.getStatus());
        plan.setStatus("CANCELED");
        plan.setUpdatedAt(LocalDateTime.now());
        SailingPlan saved = planRepository.save(plan);
        if (wasApproved) {
            Vessel vessel = plan.getVessel();
            vessel.setStatus("IN_PORT");
            vessel.setUpdatedAt(LocalDateTime.now());
            vesselRepository.save(vessel);
        }
        return toView(saved);
    }

    public Views.SailingPlanView toView(SailingPlan plan) {
        Vessel vessel = plan.getVessel();
        Long overdueHours = null;
        if ("APPROVED".equals(plan.getStatus()) && plan.getEtaReturn().isBefore(LocalDateTime.now())) {
            overdueHours = Duration.between(plan.getEtaReturn(), LocalDateTime.now()).toHours();
        }
        return new Views.SailingPlanView(
                plan.getId(),
                plan.getPlanNo(),
                vessel == null ? null : vessel.getId(),
                vessel == null ? null : vessel.getName(),
                vessel == null ? null : vessel.getTypeCode(),
                vessel == null ? null : MarinaDict.vesselTypeName(vessel.getTypeCode()),
                vessel == null || vessel.getCurrentBerth() == null ? null : vessel.getCurrentBerth().getBerthNo(),
                plan.getSkipper(),
                plan.getPhone(),
                plan.getDepartTime(),
                plan.getEtaReturn(),
                plan.getRoute(),
                plan.getCrewCount(),
                plan.getWeather(),
                plan.getStatus(),
                MarinaDict.planStatusName(plan.getStatus()),
                overdueHours,
                plan.getUpdatedAt());
    }

    /** 供前端「新建申报」预填单号 */
    public String suggestPlanNo() {
        String prefix = "SP-" + LocalDateTime.now().format(NO_SUFFIX) + "-";
        String no = prefix + String.format("%04d", planRepository.count() + 1);
        while (planRepository.existsByPlanNo(no)) {
            no = prefix + String.format("%04d", Long.parseLong(no.substring(no.length() - 4)) + 1);
        }
        return no;
    }
}
