package com.marina.berth.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.ClosureOrder;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.SailingPlanDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.ClosureOrderRepository;
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
    private final ClosureOrderRepository closureRepository;
    private final ClosureService closureService;
    private final TransactionTemplate txTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    public SailingPlanService(SailingPlanRepository planRepository,
                              VesselRepository vesselRepository,
                              BerthingRepository berthingRepository,
                              BerthingService berthingService,
                              ClosureOrderRepository closureRepository,
                              ClosureService closureService,
                              PlatformTransactionManager txManager) {
        this.planRepository = planRepository;
        this.vesselRepository = vesselRepository;
        this.berthingRepository = berthingRepository;
        this.berthingService = berthingService;
        this.closureRepository = closureRepository;
        this.closureService = closureService;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /** 当前是否有「生效中」的封航令（只判断，不加锁） */
    @Transactional(readOnly = true)
    public boolean hasEffectiveClosure() {
        return closureRepository.existsEffective();
    }

    /**
     * 取当前生效中的封航令（不加锁），用于提示文案；没有则 null。
     * 写事务里的审批闸门走 {@link ClosureService#effectiveGateAfterAutoActivate()}，
     * 会先把到点的待生效令懒生效，再判断是否仍生效。
     */
    @Transactional(readOnly = true)
    public ClosureOrder currentEffectiveClosure() {
        return closureRepository.findFirstByStatusOrderByEffectiveAtAsc("EFFECTIVE").orElse(null);
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

    /**
     * 审批通过（放行许可）。注意：批准 ≠ 实际出航 —— 批准后船仍在港（状态/泊位不动），
     * 等出航时间到由 {@link #processDueDepartures()} 或值班员 {@link #depart(Long)} 办理出港：
     * 结算离泊、船只转「出海」。这样「已批准但尚未出航」（封航要暂停的对象）与
     * 「已经出海」（封航要建返港处置项的对象）才是两条互不重叠的集合。
     */
    @Transactional
    public Views.SailingPlanView approve(Long planId) {
        // 固定锁顺序：先封航令（审批闸门，顺带把到点令懒生效），再申报，避免与封航生效事务互锁
        ClosureOrder blocking = closureService.effectiveGateAfterAutoActivate();
        SailingPlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if (blocking != null) {
            throw new BizException("封航令 " + blocking.getOrderNo() + "（"
                    + MarinaDict.closureReasonName(blocking.getReasonType())
                    + "）生效中，新的出航申报可保存为待审批，但不得批准放行");
        }
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
        if ("OUT_SEA".equals(vessel.getStatus())) {
            throw new BizException("船只 " + vessel.getName() + " 当前已出海，不能重复批准出航");
        }
        plan.setStatus("APPROVED");
        plan.setApprovedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        SailingPlan saved = planRepository.save(plan);
        // 船只与泊位保持原状（仍在港、仍靠泊），实际出港走 depart()
        return toView(saved);
    }

    /**
     * 办理实际出港（已批准的申报到出航时间）：仍占泊位则先结算离泊，再把船只转「出海」。
     * 只接受 APPROVED 申报；SUSPENDED 的被封航拦住，出不了港。
     */
    @Transactional
    public Views.SailingPlanView depart(Long planId) {
        SailingPlan plan = planRepository.findByIdForUpdate(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if ("SUSPENDED".equals(plan.getStatus())) {
            throw new BizException("申报 " + plan.getPlanNo() + " 因封航暂停放行，出港被拦截；解除封航后才能办理出航");
        }
        if (!"APPROVED".equals(plan.getStatus())) {
            throw new BizException("只有已批准的申报可以办理出港，当前状态：" + MarinaDict.planStatusName(plan.getStatus()));
        }
        return toView(doDepart(plan));
    }

    /** 出港主体（调用方必须已对申报加锁且处于事务中） */
    private SailingPlan doDepart(SailingPlan plan) {
        Long planId = plan.getId();
        LocalDateTime now = LocalDateTime.now();
        Vessel vessel = vesselRepository.findByIdForUpdate(plan.getVessel().getId())
                .orElseThrow(() -> new BizException("船只不存在"));
        if ("OUT_SEA".equals(vessel.getStatus())) {
            return plan; // 已出港（重复点击/定时任务与手动并发）幂等
        }
        // 出港清泊位：还占着泊位的按当前时间结算离泊
        Berthing open = berthingRepository
                .findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vessel.getId(), "IN_PORT").orElse(null);
        if (open != null) {
            berthingService.depart(open.getId(), now);
            entityManager.flush();
            entityManager.clear();
            plan = planRepository.findById(planId)
                    .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
            vessel = vesselRepository.findById(plan.getVessel().getId())
                    .orElseThrow(() -> new BizException("船只不存在"));
        }
        vessel.setStatus("OUT_SEA");
        vessel.setUpdatedAt(now);
        vesselRepository.save(vessel);
        plan.setUpdatedAt(now);
        return planRepository.save(plan);
    }

    /**
     * 定时出港：到了出航时间仍「已批准」的申报自动办理出港。
     * 本方法不开事务；每条申报用 {@link TransactionTemplate} 起独立事务，
     * 单条失败（如封航生效瞬间被暂停拦截）整笔回滚，下一轮状态已变自然跳过。
     */
    public void processDueDepartures() {
        LocalDateTime now = LocalDateTime.now();
        for (SailingPlan p : planRepository.findAllByStatusOrderByDepartTimeDesc("APPROVED")) {
            if (!p.getDepartTime().isAfter(now)) {
                Long id = p.getId();
                try {
                    txTemplate.executeWithoutResult(status -> {
                        SailingPlan locked = planRepository.findByIdForUpdate(id).orElse(null);
                        if (locked != null && "APPROVED".equals(locked.getStatus())) {
                            doDepart(locked);
                        }
                    });
                } catch (RuntimeException ignore) {
                    // 封航暂停 / 并发改动等：下一轮再看
                }
            }
        }
    }

    /** 确认返航 */
    @Transactional
    public Views.SailingPlanView markReturn(Long planId) {
        SailingPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if ("SUSPENDED".equals(plan.getStatus())) {
            throw new BizException("申报 " + plan.getPlanNo() + " 因封航暂停放行，不能直接登记返航；"
                    + "请在「封航返港」处置台为该船登记安全靠泊");
        }
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

    /** 取消申报（暂停放行的申报走封航流程，不能直接取消） */
    @Transactional
    public Views.SailingPlanView cancel(Long planId) {
        SailingPlan plan = planRepository.findById(planId)
                .orElseThrow(() -> new BizException("申报单不存在：id=" + planId));
        if ("SUSPENDED".equals(plan.getStatus())) {
            throw new BizException("申报 " + plan.getPlanNo() + " 已因封航暂停放行，不能直接改成取消；"
                    + "解除封航后会自动恢复为可继续审批");
        }
        if (!"PENDING".equals(plan.getStatus()) && !"APPROVED".equals(plan.getStatus())) {
            throw new BizException("已返航或已取消的申报不能再取消，当前状态：" + MarinaDict.planStatusName(plan.getStatus()));
        }
        plan.setStatus("CANCELED");
        plan.setUpdatedAt(LocalDateTime.now());
        SailingPlan saved = planRepository.save(plan);
        // 已批准但尚未出港的申报被取消：船本就还在港，无需再动船只/泊位状态
        return toView(saved);
    }

    public Views.SailingPlanView toView(SailingPlan plan) {
        Vessel vessel = plan.getVessel();
        Long overdueHours = null;
        // 超期未返：船已实际出港（在海）且预计返回时间已过。仅「已批准但未出港」不算在海，不算超期
        if ("APPROVED".equals(plan.getStatus()) && vessel != null && "OUT_SEA".equals(vessel.getStatus())
                && plan.getEtaReturn().isBefore(LocalDateTime.now())) {
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
                vessel != null && "OUT_SEA".equals(vessel.getStatus()),
                plan.getApprovedAt(),
                plan.getSuspendedAt(),
                plan.getSuspendedReason(),
                plan.getResumedAt(),
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
