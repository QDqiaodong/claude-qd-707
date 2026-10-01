package com.marina.berth.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.ClosureLog;
import com.marina.berth.domain.ClosurePlanEffect;
import com.marina.berth.domain.DispositionContact;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.NavigationClosureOrder;
import com.marina.berth.domain.ReturnDisposition;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.ClosureDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.ClosureLogRepository;
import com.marina.berth.repository.ClosureOrderRepository;
import com.marina.berth.repository.ClosurePlanEffectRepository;
import com.marina.berth.repository.DispositionContactRepository;
import com.marina.berth.repository.ReturnDispositionRepository;
import com.marina.berth.repository.SailingPlanRepository;
import com.marina.berth.repository.VesselRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 模块六：封航与返港处置台 —— 本模块是全仓规则最密的一处。
 *
 * <p><b>封航令四阶段</b>（每次流转都先对封航令行加悲观写锁，串行化双人并发）：
 * <pre>
 *   PENDING 待生效 ──到点/手动激活──▶ EFFECTIVE 生效中 ──申请解除──▶ RELEASING 解除中
 *                                          │                            │
 *                                          └──────── 风险项清零 ─────────┴──▶ ARCHIVED 已归档
 * </pre>
 * 生效瞬间：①已批准且已出海的船 → 生成返港处置项（一船一令一条，库表唯一键兜底）；
 *           ②已批准但尚未出航的申报 → 置 SUSPENDED 暂停放行（绝不改成取消/返航），按出航时间发暂停序号；
 *           ③待审批申报保持 PENDING 不动，但审批入口被 {@link #currentBlockingOrder()} 实时拦住。
 *
 * <p><b>并发与幂等</b>：处置项行锁 + (令,船) 唯一键；联系记录 (处置项, clientRequestId) 唯一键。
 * 两名值班员同时确认、双击、超时重试：要么命中已有结果原样返回，要么撞唯一键回滚，
 * 不会出现重复记录、状态倒退、泊位重复占用。
 *
 * <p><b>安全靠泊</b>（{@link #safeBerth}）：处置项 / 出航申报 / 船只 / 靠泊记录 / 泊位
 * 在一个事务里一次提交，任何一步抛错整笔回退，并明确告知值班员哪些数据没有改变。
 */
@Service
public class ClosureService {

    private static final DateTimeFormatter NO_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final ClosureOrderRepository orderRepository;
    private final ReturnDispositionRepository dispositionRepository;
    private final DispositionContactRepository contactRepository;
    private final ClosurePlanEffectRepository effectRepository;
    private final ClosureLogRepository logRepository;
    private final SailingPlanRepository planRepository;
    private final VesselRepository vesselRepository;
    private final BerthRepository berthRepository;
    private final BerthingRepository berthingRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public ClosureService(ClosureOrderRepository orderRepository,
                          ReturnDispositionRepository dispositionRepository,
                          DispositionContactRepository contactRepository,
                          ClosurePlanEffectRepository effectRepository,
                          ClosureLogRepository logRepository,
                          SailingPlanRepository planRepository,
                          VesselRepository vesselRepository,
                          BerthRepository berthRepository,
                          BerthingRepository berthingRepository) {
        this.orderRepository = orderRepository;
        this.dispositionRepository = dispositionRepository;
        this.contactRepository = contactRepository;
        this.effectRepository = effectRepository;
        this.logRepository = logRepository;
        this.planRepository = planRepository;
        this.vesselRepository = vesselRepository;
        this.berthRepository = berthRepository;
        this.berthingRepository = berthingRepository;
    }

    // ============================================================
    // 封航令：发布 / 激活 / 解除 / 归档
    // ============================================================

    /** 发布封航令。effectiveAt 为空或不晚于当前时间 → 立即生效；否则进入待生效。 */
    @Transactional
    public Views.ClosureOrderView create(ClosureDtos.ClosureCreateReq req) {
        String reasonType = req.reasonType() == null ? "" : req.reasonType().trim().toUpperCase();
        if (!MarinaDict.isValidClosureReason(reasonType)) {
            throw new BizException("封航事由只能是 TYPHOON 台风 / MARITIME_CONTROL 海事管制 / WATER_RISK 突发水域风险");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw new BizException("封航标题不能为空");
        }
        if (req.description() == null || req.description().isBlank()) {
            throw new BizException("详细说明不能为空");
        }
        String operator = operator(req.issuedBy());
        // 同一时刻只允许一条未归档封航令：active_guard 生成列 + 唯一索引在数据库兜底，
        // 这里先给一句人话提示；并发两条发布时由唯一键拒绝第二条。
        if (orderRepository.findFirstByStatusNotOrderByIdDesc("ARCHIVED").isPresent()) {
            NavigationClosureOrder open = orderRepository.findFirstByStatusNotOrderByIdDesc("ARCHIVED").get();
            throw new BizException("已有未归档封航令 " + open.getOrderNo() + "（"
                    + MarinaDict.closureStatusName(open.getStatus()) + "），请先归档后再发布新令");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime effectiveAt = req.effectiveAt() == null ? now : req.effectiveAt();
        if (effectiveAt.isBefore(now.minusMinutes(1))) {
            throw new BizException("生效时间不能早于当前时间");
        }

        NavigationClosureOrder order = new NavigationClosureOrder();
        order.setOrderNo(nextOrderNo(effectiveAt));
        order.setReasonType(reasonType);
        order.setTitle(req.title().trim());
        order.setDescription(req.description().trim());
        order.setIssuedBy(operator);
        order.setEffectiveAt(effectiveAt);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        // 先给待生效/生效中状态，active_guard 生成列随之恒为 1，由库表唯一键兜底并发双发
        order.setStatus(effectiveAt.isAfter(now) ? "PENDING" : "EFFECTIVE");
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("已有未归档封航令，同一时间只能执行一条封航令");
        }
        writeLog(order, "CREATE", "发布封航令，事由：" + MarinaDict.closureReasonName(reasonType)
                + "，计划生效时间 " + effectiveAt, operator);

        // 生效时间已到 → 立即激活（同一事务内完成，前端立刻看到处置项与暂停申报）
        if (!effectiveAt.isAfter(now)) {
            order = activateInternal(order, operator);
        }
        return toOrderView(order, counts(order.getId()), suspendedCount(order.getId()));
    }

    /** 定时：每 30 秒把到点的待生效封航令激活（发布时间已到且仍处于流程中）。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 5_000L)
    @Transactional
    public void activateDueOrders() {
        for (NavigationClosureOrder order :
                orderRepository.findByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAsc(
                        "PENDING", LocalDateTime.now())) {
            NavigationClosureOrder locked = orderRepository.findByIdForUpdate(order.getId()).orElse(null);
            if (locked != null && "PENDING".equals(locked.getStatus())) {
                activateInternal(locked, locked.getIssuedBy());
            }
        }
    }

    /** 手动激活待生效封航令（到点前不允许）。 */
    @Transactional
    public Views.ClosureOrderView activate(Long orderId, String operator) {
        NavigationClosureOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + orderId));
        if (!"PENDING".equals(order.getStatus())) {
            throw new BizException("封航令 " + order.getOrderNo() + " 当前为「"
                    + MarinaDict.closureStatusName(order.getStatus()) + "」，无需激活");
        }
        if (order.getEffectiveAt().isAfter(LocalDateTime.now())) {
            throw new BizException("未到发布生效时间 " + order.getEffectiveAt() + "，不能提前激活");
        }
        order = activateInternal(order, operator(operator));
        return toOrderView(order, counts(order.getId()), suspendedCount(order.getId()));
    }

    /**
     * 真正的生效动作（调用方必须已持有封航令行锁）：
     * 生成在海船只处置项 + 暂停已批准未出航申报。
     */
    private NavigationClosureOrder activateInternal(NavigationClosureOrder order, String operator) {
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("EFFECTIVE");
        order.setActivatedAt(now);
        order.setUpdatedAt(now);
        order = orderRepository.saveAndFlush(order);

        String holdReason = "封航令 " + order.getOrderNo() + "（"
                + MarinaDict.closureReasonName(order.getReasonType()) + "：" + order.getTitle() + "）已生效，暂停放行";

        // ① 已出海船只：一船一条返港处置项（按船找当前已批准申报挂接，找不到也生成）
        List<Vessel> atSea = vesselRepository.findAllByStatusOrderByRegNoAsc("OUT_SEA");
        for (Vessel vessel : atSea) {
            if (dispositionRepository.findByClosureOrderIdAndVesselId(order.getId(), vessel.getId()).isPresent()) {
                continue;
            }
            SailingPlan activePlan = latestApprovedPlan(vessel.getId());
            ReturnDisposition disp = new ReturnDisposition();
            disp.setClosureOrder(order);
            disp.setVessel(vessel);
            disp.setSailingPlan(activePlan);
            disp.setStatus("TO_CONTACT");
            disp.setCreatedAt(now);
            disp.setUpdatedAt(now);
            try {
                dispositionRepository.saveAndFlush(disp);
            } catch (DataIntegrityViolationException e) {
                // 另一事务已为该船插入（并发激活/重试）：跳过，绝不出第二条
                continue;
            }
            writeLog(order, "DISP_CREATE", "生成返港处置项：" + vessel.getName()
                    + (activePlan == null ? "" : "（申报 " + activePlan.getPlanNo() + "）"), operator);
        }

        // ② 已批准但尚未出航的申报：暂停放行。船还在港（IN_PORT）且无在泊离泊 = 未出航。
        List<SailingPlan> toSuspend = new ArrayList<>();
        for (SailingPlan plan : planRepository.findAllByStatusOrderByDepartTimeDesc("APPROVED")) {
            Vessel vessel = plan.getVessel();
            if (!"OUT_SEA".equals(vessel.getStatus())) {
                toSuspend.add(plan);
            }
        }
        toSuspend.sort(Comparator.comparing(SailingPlan::getDepartTime)
                .thenComparing(SailingPlan::getId));
        int seq = 1;
        for (SailingPlan plan : toSuspend) {
            plan.setStatus("SUSPENDED");
            plan.setHoldByOrder(order);
            plan.setHoldSeq(seq);
            plan.setHoldReason(holdReason);
            plan.setUpdatedAt(now);
            planRepository.save(plan);

            ClosurePlanEffect effect = new ClosurePlanEffect();
            effect.setClosureOrder(order);
            effect.setSailingPlan(plan);
            effect.setEffectType("SUSPENDED");
            effect.setPlanStatusSnap("APPROVED");
            effect.setHoldSeq(seq);
            effect.setRemark(holdReason);
            effect.setOperator(operator);
            effect.setCreatedAt(now);
            effectRepository.save(effect);
            writeLog(order, "SUSPEND_PLAN", "申报 " + plan.getPlanNo() + "（"
                    + plan.getVessel().getName() + "）已批准未出航，暂停放行，审批顺序 #" + seq, operator);
            seq++;
        }

        writeLog(order, "ACTIVATE", "封航令生效；在海船只 " + atSea.size() + " 艘已生成返港处置项，"
                + "暂停放行申报 " + (seq - 1) + " 条；待审批申报仍可保存但不得批准", operator);
        return order;
    }

    /** 申请解除：进入解除中（风险项未清零也允许，只是不能归档）。 */
    @Transactional
    public Views.ClosureOrderView release(Long orderId, ClosureDtos.ReleaseReq req) {
        NavigationClosureOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + orderId));
        if (!"EFFECTIVE".equals(order.getStatus())) {
            throw new BizException("只有生效中的封航令可以申请解除，当前状态："
                    + MarinaDict.closureStatusName(order.getStatus()));
        }
        String operator = operator(req == null ? null : req.operator());
        LocalDateTime now = LocalDateTime.now();
        order.setStatus("RELEASING");
        order.setReleasedAt(now);
        order.setReleaseRemark(req == null || req.remark() == null ? null : req.remark().trim());
        order.setUpdatedAt(now);
        order = orderRepository.saveAndFlush(order);
        long risk = dispositionRepository
                .findByClosureOrderIdAndStatusNotOrderByIdAsc(orderId, "BERTHED").size();
        writeLog(order, "RELEASE", "申请解除，进入解除中；剩余风险处置项 " + risk
                + " 条，清零后方可归档（暂停申报不会被自动批准）", operator);
        return toOrderView(order, counts(order.getId()), suspendedCount(order.getId()));
    }

    /**
     * 归档：失联升级 / 已确认返港但尚未安全靠泊的处置项必须全部清零。
     * 归档时把暂停放行的申报【恢复为可继续审批】（PENDING），保留暂停原因，绝不自动批准；
     * hold_seq 保留，前端按它恢复原审批顺序。
     */
    @Transactional
    public Views.ClosureOrderView archive(Long orderId, String operatorName) {
        NavigationClosureOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + orderId));
        if (!"EFFECTIVE".equals(order.getStatus()) && !"RELEASING".equals(order.getStatus())) {
            throw new BizException("只有生效中或解除中的封航令可以归档，当前状态："
                    + MarinaDict.closureStatusName(order.getStatus()));
        }
        List<ReturnDisposition> risks = dispositionRepository
                .findByClosureOrderIdAndStatusNotOrderByIdAsc(orderId, "BERTHED");
        if (!risks.isEmpty()) {
            List<String> names = risks.stream().map(d -> d.getVessel().getName()).toList();
            throw new BizException("仍有 " + risks.size() + " 条风险处置项未清零（"
                    + String.join("、", names) + "），只能保持「解除中」，全部安全靠泊后才能归档");
        }
        String operator = operator(operatorName);
        LocalDateTime now = LocalDateTime.now();

        // 恢复暂停申报：状态回 PENDING（可继续审批），保留 hold_seq/hold_reason，仅摘掉封航令挂接
        List<SailingPlan> suspended = planRepository.findAllByHoldByOrderIdOrderByHoldSeqAscIdAsc(orderId);
        int restored = 0;
        for (SailingPlan plan : suspended) {
            if (!"SUSPENDED".equals(plan.getStatus())) {
                continue;
            }
            plan.setStatus("PENDING");
            plan.setHoldByOrder(null);
            plan.setUpdatedAt(now);
            planRepository.save(plan);

            ClosurePlanEffect effect = new ClosurePlanEffect();
            effect.setClosureOrder(order);
            effect.setSailingPlan(plan);
            effect.setEffectType("RESTORED");
            effect.setPlanStatusSnap("SUSPENDED");
            effect.setHoldSeq(plan.getHoldSeq());
            effect.setRemark("解除封航，恢复为可继续审批，原审批顺序 #" + plan.getHoldSeq() + "，未自动批准");
            effect.setOperator(operator);
            effect.setCreatedAt(now);
            effectRepository.save(effect);
            restored++;
        }

        order.setStatus("ARCHIVED");
        order.setArchivedAt(now);
        order.setArchivedBy(operator);
        order.setUpdatedAt(now);
        order = orderRepository.saveAndFlush(order);
        writeLog(order, "ARCHIVE", "风险项清零，封航令归档；" + restored
                + " 条暂停申报恢复为可继续审批（保留原审批顺序与暂停原因，未自动批准）", operator);
        return toOrderView(order, counts(order.getId()), suspendedCount(order.getId()));
    }

    // ============================================================
    // 出航申报侧的封航联动钩子（被 SailingPlanService 调用）
    // ============================================================

    /**
     * 返回当前真正拦住出航的封航令（生效中 或 解除中 —— 解除期间仍未恢复审批）。
     * 同时把已到点的待生效令惰性激活，避免定时任务还没跑到时出现放行窗口。
     */
    @Transactional
    public Optional<NavigationClosureOrder> currentBlockingOrder() {
        Optional<NavigationClosureOrder> open = orderRepository.findFirstByStatusNotOrderByIdDesc("ARCHIVED");
        if (open.isEmpty()) {
            return Optional.empty();
        }
        NavigationClosureOrder order = open.get();
        if ("PENDING".equals(order.getStatus())
                && !order.getEffectiveAt().isAfter(LocalDateTime.now())) {
            NavigationClosureOrder locked = orderRepository.findByIdForUpdate(order.getId()).orElse(null);
            if (locked != null && "PENDING".equals(locked.getStatus())) {
                activateInternal(locked, locked.getIssuedBy());
            }
            order = orderRepository.findById(order.getId()).orElse(order);
        }
        if ("EFFECTIVE".equals(order.getStatus()) || "RELEASING".equals(order.getStatus())) {
            return Optional.of(order);
        }
        return Optional.empty();
    }

    /** 新申报在封航期间被保存为待审批：补一条 BLOCKED_NEW 影响台账（申报本身照常保存）。 */
    @Transactional
    public void recordBlockedNewPlan(SailingPlan plan, NavigationClosureOrder order) {
        LocalDateTime now = LocalDateTime.now();
        ClosurePlanEffect effect = new ClosurePlanEffect();
        effect.setClosureOrder(order);
        effect.setSailingPlan(plan);
        effect.setEffectType("BLOCKED_NEW");
        effect.setPlanStatusSnap(plan.getStatus());
        effect.setRemark("封航期间新建申报，保存为待审批；封航解除归档前不得批准");
        effect.setOperator("值班员");
        effect.setCreatedAt(now);
        effectRepository.save(effect);
    }

    // ============================================================
    // 返港处置：联系 / 确认返港 / 失联升级（幂等 + 状态只进不退）
    // ============================================================

    @Transactional
    public Views.DispositionView contact(Long dispositionId, ClosureDtos.ContactReq req) {
        if (req == null || req.result() == null || req.result().isBlank()) {
            throw new BizException("联系结果不能为空");
        }
        String result = req.result().trim().toUpperCase();
        if (!"CONTACTED".equals(result) && !"CONFIRMED_RETURN".equals(result)
                && !"LOST_CONTACT".equals(result) && !"REACHED".equals(result)) {
            throw new BizException("联系结果只能是 CONTACTED / CONFIRMED_RETURN / LOST_CONTACT / REACHED");
        }
        String operator = operator(req.operator());
        String reqId = req.clientRequestId();

        ReturnDisposition disp = dispositionRepository.findByIdForUpdate(dispositionId)
                .orElseThrow(() -> new BizException("返港处置项不存在：id=" + dispositionId));
        // 幂等：同一请求令牌重试 → 原样返回首次结果，不写第二条、不二次推进状态
        if (reqId != null && !reqId.isBlank()) {
            DispositionContact existing = contactRepository
                    .findByDispositionIdAndClientRequestId(dispositionId, reqId).orElse(null);
            if (existing != null) {
                return toDispositionView(reloadDisposition(dispositionId), true);
            }
        }
        if ("BERTHED".equals(disp.getStatus())) {
            throw new BizException(disp.getVessel().getName() + " 已安全靠泊，处置项已结，无需再联系");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime contactAt = req.contactTime() == null ? now : req.contactTime();
        String from = disp.getStatus();
        String remark = req.remark() == null ? null : req.remark().trim();

        switch (result) {
            case "CONTACTED" -> {
                if (disp.getContactedAt() == null) {
                    disp.setContactedAt(contactAt);
                }
            }
            case "CONFIRMED_RETURN" -> applyConfirmed(disp, req.plannedBerthId(), contactAt);
            case "LOST_CONTACT" -> {
                // 待联系 / 已确认返港都可升级为失联（确认后又联系不上）
                disp.setStatus("LOST_CONTACT");
                if (disp.getEscalatedAt() == null) {
                    disp.setEscalatedAt(contactAt);
                }
            }
            case "REACHED" -> {
                if (!"LOST_CONTACT".equals(disp.getStatus())) {
                    throw new BizException("只有失联升级的处置项可以登记「重新取得联系」，当前状态："
                            + MarinaDict.dispositionStatusName(disp.getStatus()));
                }
                applyConfirmed(disp, req.plannedBerthId(), contactAt);
            }
            default -> throw new BizException("不支持的联系结果：" + result);
        }
        // 状态只进不退：新状态阶段序不得低于旧状态（LOST_CONTACT 与 CONFIRMED_RETURN 视为同一层级，
        // 失联后重新取得联系允许 CONFIRMED_RETURN ↔ LOST_CONTACT 之间按上面的分支受控切换）
        if (rank(disp.getStatus()) < rank(from)) {
            throw new BizException("处置状态不能倒退：" + MarinaDict.dispositionStatusName(from)
                    + " → " + MarinaDict.dispositionStatusName(disp.getStatus()));
        }
        disp.setUpdatedAt(now);
        try {
            dispositionRepository.saveAndFlush(disp);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("操作冲突，该处置项可能已被另一名值班员处理，请刷新后查看最新状态");
        }

        DispositionContact contact = new DispositionContact();
        contact.setDisposition(disp);
        contact.setContactTime(contactAt);
        contact.setResult(result);
        contact.setRemark(remark);
        contact.setOperator(operator);
        contact.setClientRequestId(reqId == null || reqId.isBlank() ? null : reqId);
        contact.setCreatedAt(now);
        try {
            contactRepository.saveAndFlush(contact);
        } catch (DataIntegrityViolationException e) {
            // 同令牌并发：标记回滚，由事务管理器回退本次全部改动
            throw new BizException("该操作已在处理中（重复提交），已为你保留首次结果，请刷新查看");
        }
        writeLog(disp.getClosureOrder(), "DISP_" + result,
                disp.getVessel().getName() + "：" + MarinaDict.dispositionStatusName(from) + " → "
                        + MarinaDict.dispositionStatusName(disp.getStatus())
                        + (remark == null || remark.isEmpty() ? "" : "；备注：" + remark), operator);

        entityManager.flush();
        return toDispositionView(reloadDisposition(dispositionId), false);
    }

    private void applyConfirmed(ReturnDisposition disp, Long plannedBerthId, LocalDateTime at) {
        if (plannedBerthId != null) {
            Berth berth = plannedBerthId == null ? null : berthRepository.findById(plannedBerthId).orElse(null);
            if (berth == null) {
                throw new BizException("预约泊位不存在：id=" + plannedBerthId);
            }
            if (!"FREE".equals(berth.getStatus())) {
                throw new BizException("泊位 " + berth.getBerthNo() + " 当前为「"
                        + MarinaDict.berthStatusName(berth.getStatus()) + "」，不能预约为返港泊位");
            }
            if (disp.getVessel().getLengthM().compareTo(berth.getLengthM()) > 0
                    || disp.getVessel().getDraftM().compareTo(berth.getMaxDraftM()) > 0
                    || !MarinaDict.allowTypeContains(berth.getAllowType(), disp.getVessel().getTypeCode())) {
                throw new BizException("泊位 " + berth.getBerthNo() + " 不适泊该船（船长/吃水/船型受限），请换一个泊位");
            }
            disp.setPlannedBerth(berth);
        }
        disp.setStatus("CONFIRMED_RETURN");
        if (disp.getContactedAt() == null) {
            disp.setContactedAt(at);
        }
        disp.setConfirmedAt(at);
    }

    /**
     * 安全靠泊：全需求一致性要求最高的一笔。
     * 处置项 + 船只 + 出航申报 + 靠泊记录 + 泊位 在同一事务提交：
     * 任何一步失败 → 整笔回退，下面列出的数据全部保持原值。
     */
    @Transactional
    public Views.SafeBerthResult safeBerth(Long dispositionId, ClosureDtos.SafeBerthReq req) {
        if (req == null || req.berthId() == null) {
            throw new BizException("请选择接纳返港船只的泊位");
        }
        String operator = operator(req.operator());
        String reqId = req.clientRequestId();

        ReturnDisposition disp = dispositionRepository.findByIdForUpdate(dispositionId)
                .orElseThrow(() -> new BizException("返港处置项不存在：id=" + dispositionId));
        // 幂等：已经安全靠泊，重复点击/超时重试直接回放上一次结果
        if ("BERTHED".equals(disp.getStatus())) {
            return replaySafeBerth(disp);
        }
        if (reqId != null && !reqId.isBlank()) {
            DispositionContact existing = contactRepository
                    .findByDispositionIdAndClientRequestId(dispositionId, reqId).orElse(null);
            if (existing != null && "BERTHED".equals(existing.getResult())) {
                return replaySafeBerth(dispositionRepository.findById(dispositionId).orElse(disp));
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime berthAt = req.berthTime() == null ? now : req.berthTime();

        // —— 第 1 步：锁泊位并校验（行锁防两艘船并发占同一泊位）——
        Berth berth = berthRepository.findByIdForUpdate(req.berthId())
                .orElseThrow(() -> safeBerthFailure("id=" + req.berthId(), "泊位不存在"));
        Vessel vessel = vesselRepository.findById(disp.getVessel().getId())
                .orElseThrow(() -> safeBerthFailure(berth.getBerthNo(), "船只台账不存在"));
        if ("DISABLED".equals(berth.getStatus())) {
            throw safeBerthFailure(berth.getBerthNo(), "泊位处于停用维修");
        }
        if (!"FREE".equals(berth.getStatus())
                || berthingRepository.findFirstByBerthIdAndStatusOrderByBerthTimeDesc(berth.getId(), "IN_PORT").isPresent()) {
            throw safeBerthFailure(berth.getBerthNo(), "泊位已被其他船只占用（可能是并发占用）");
        }
        if ("MAINTENANCE".equals(vessel.getStatus()) || "TERMINATED".equals(vessel.getStatus())) {
            throw safeBerthFailure(berth.getBerthNo(), "船只处于" + MarinaDict.vesselStatusName(vessel.getStatus()) + "，不能靠泊");
        }
        if (berthingRepository.findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vessel.getId(), "IN_PORT").isPresent()) {
            throw safeBerthFailure(berth.getBerthNo(), "该船已在其他泊位在泊");
        }
        if (vessel.getLengthM().compareTo(berth.getLengthM()) > 0) {
            throw safeBerthFailure(berth.getBerthNo(), "船长 " + vessel.getLengthM() + "m 超过泊位长度");
        }
        if (vessel.getDraftM().compareTo(berth.getMaxDraftM()) > 0) {
            throw safeBerthFailure(berth.getBerthNo(), "船只吃水超过泊位最大吃水");
        }
        if (!MarinaDict.allowTypeContains(berth.getAllowType(), vessel.getTypeCode())) {
            throw safeBerthFailure(berth.getBerthNo(), "泊位不允许该船型停靠");
        }

        List<String> changedItems = new ArrayList<>();

        // —— 第 2 步：写靠泊记录 ——
        Berthing berthing = new Berthing();
        berthing.setRecordNo(nextBerthingNo(berthAt));
        berthing.setBerth(berth);
        berthing.setVessel(vessel);
        berthing.setBerthTime(berthAt);
        berthing.setStatus("IN_PORT");
        berthing.setUpdatedAt(now);
        try {
            berthing = berthingRepository.saveAndFlush(berthing);
        } catch (DataIntegrityViolationException e) {
            throw safeBerthFailure(berth.getBerthNo(), "靠泊记录写入冲突");
        }
        changedItems.add("靠泊记录 " + berthing.getRecordNo() + " 已建立（在泊）");

        // —— 第 3 步：泊位 → 在泊 ——
        berth.setStatus("IN_PORT");
        berth.setUpdatedAt(now);
        berthRepository.save(berth);
        changedItems.add("泊位 " + berth.getBerthNo() + " 状态改为在泊");

        // —— 第 4 步：船只 → 在港 ——
        vessel.setStatus("IN_PORT");
        vessel.setUpdatedAt(now);
        vesselRepository.save(vessel);
        changedItems.add("船只 " + vessel.getName() + " 状态改为在港");

        // —— 第 5 步：关联的已批准申报 → 已返航 ——
        SailingPlan plan = disp.getSailingPlan();
        if (plan != null) {
            plan = planRepository.findById(plan.getId()).orElse(null);
        }
        if (plan == null) {
            plan = latestApprovedPlan(vessel.getId());
        }
        String planStatusBefore = plan == null ? null : plan.getStatus();
        if (plan != null && ("APPROVED".equals(plan.getStatus()) || "SUSPENDED".equals(plan.getStatus()))) {
            plan.setStatus("RETURNED");
            plan.setUpdatedAt(now);
            planRepository.save(plan);
            changedItems.add("出航申报 " + plan.getPlanNo() + " 状态改为已返航");
        }

        // —— 第 6 步：处置项 → 已安全靠泊（终态）——
        disp.setStatus("BERTHED");
        disp.setBerthedAt(berthAt);
        disp.setBerth(berth);
        disp.setBerthingId(berthing.getId());
        if (disp.getConfirmedAt() == null) {
            disp.setConfirmedAt(berthAt);
        }
        if (disp.getContactedAt() == null) {
            disp.setContactedAt(berthAt);
        }
        disp.setUpdatedAt(now);
        dispositionRepository.save(disp);
        changedItems.add("返港处置项状态改为已安全靠泊");

        // —— 第 7 步：联系记录补一条 BERTHED ——
        DispositionContact contact = new DispositionContact();
        contact.setDisposition(disp);
        contact.setContactTime(berthAt);
        contact.setResult("BERTHED");
        contact.setRemark(req.remark() == null || req.remark().isBlank()
                ? "安全靠泊 " + berth.getBerthNo()
                : req.remark().trim() + "（靠泊 " + berth.getBerthNo() + "）");
        contact.setOperator(operator);
        contact.setClientRequestId(reqId == null || reqId.isBlank() ? null : reqId);
        contact.setCreatedAt(now);
        contactRepository.save(contact);

        writeLog(disp.getClosureOrder(), "DISP_BERTHED",
                vessel.getName() + " 安全靠泊 " + berth.getBerthNo() + "，靠泊记录 " + berthing.getRecordNo()
                        + (plan != null ? "，申报 " + plan.getPlanNo() + " 同步改为已返航" : "")
                        + "；处置项/船只/申报/靠泊记录/泊位一次提交一致", operator);

        entityManager.flush();
        entityManager.clear();

        ReturnDisposition fresh = dispositionRepository.findById(dispositionId).orElseThrow();
        Berth freshBerth = berthRepository.findById(berth.getId()).orElseThrow();
        Vessel freshVessel = vesselRepository.findById(vessel.getId()).orElseThrow();
        SailingPlan freshPlan = plan == null ? null : planRepository.findById(plan.getId()).orElse(null);
        Berthing freshBerthing = berthingRepository.findById(berthing.getId()).orElseThrow();

        return new Views.SafeBerthResult(
                fresh.getId(), fresh.getStatus(), MarinaDict.dispositionStatusName(fresh.getStatus()),
                freshVessel.getId(), freshVessel.getName(), freshVessel.getStatus(),
                MarinaDict.vesselStatusName(freshVessel.getStatus()),
                freshPlan == null ? null : freshPlan.getId(),
                freshPlan == null ? null : freshPlan.getPlanNo(),
                freshPlan == null ? null : freshPlan.getStatus(),
                freshPlan == null ? null : MarinaDict.planStatusName(freshPlan.getStatus()),
                freshBerthing.getId(), freshBerthing.getRecordNo(),
                freshBerth.getId(), freshBerth.getBerthNo(), freshBerth.getStatus(),
                MarinaDict.berthStatusName(freshBerth.getStatus()),
                fresh.getBerthedAt(), List.copyOf(changedItems), toContactViews(fresh.getId()));
    }

    private Views.SafeBerthResult replaySafeBerth(ReturnDisposition disp) {
        Long berthingId = disp.getBerthingId();
        Berthing berthing = berthingId == null ? null : berthingRepository.findById(berthingId).orElse(null);
        Berth berth = disp.getBerth() == null ? null : berthRepository.findById(disp.getBerth().getId()).orElse(null);
        Vessel vessel = vesselRepository.findById(disp.getVessel().getId()).orElseThrow();
        SailingPlan plan = disp.getSailingPlan() == null ? null
                : planRepository.findById(disp.getSailingPlan().getId()).orElse(null);
        List<String> changedItems = List.of(
                "该船已安全靠泊（幂等回放，未重复占用泊位、未重复建靠泊记录）",
                "泊位 " + (berth == null ? "—" : berth.getBerthNo()),
                "靠泊记录 " + (berthing == null ? "—" : berthing.getRecordNo()));
        return new Views.SafeBerthResult(
                disp.getId(), disp.getStatus(), MarinaDict.dispositionStatusName(disp.getStatus()),
                vessel.getId(), vessel.getName(), vessel.getStatus(),
                MarinaDict.vesselStatusName(vessel.getStatus()),
                plan == null ? null : plan.getId(), plan == null ? null : plan.getPlanNo(),
                plan == null ? null : plan.getStatus(),
                plan == null ? null : MarinaDict.planStatusName(plan.getStatus()),
                berthing == null ? null : berthing.getId(),
                berthing == null ? null : berthing.getRecordNo(),
                berth == null ? null : berth.getId(), berth == null ? null : berth.getBerthNo(),
                berth == null ? null : berth.getStatus(),
                berth == null ? null : MarinaDict.berthStatusName(berth.getStatus()),
                disp.getBerthedAt(), changedItems, toContactViews(disp.getId()));
    }

    /**
     * 安全靠泊任一步失败：抛出即触发整笔事务回退。
     * 文案逐项列出「哪些数据没有改变」，让值班员明确知道库里保持原值。
     */
    private BizException safeBerthFailure(String berthText, String reason) {
        return new BizException("安全靠泊未完成：" + reason + "。整笔操作已回退，以下数据均未改变："
                + "返港处置项状态、出航申报状态、船只状态、靠泊记录、泊位 "
                + (berthText == null ? "" : berthText) + " 状态。请调整后重新办理。");
    }

    // ============================================================
    // 查询：处置台详情 / 列表 / 可接纳泊位
    // ============================================================

    @Transactional(readOnly = true)
    public List<Views.ClosureOrderView> list(boolean includeArchived) {
        List<NavigationClosureOrder> orders = includeArchived
                ? orderRepository.findAllByOrderByCreatedAtDescIdDesc()
                : orderRepository.findAllByStatusNotOrderByCreatedAtDescIdDesc("ARCHIVED");
        List<Views.ClosureOrderView> list = new ArrayList<>();
        for (NavigationClosureOrder order : orders) {
            list.add(toOrderView(order, counts(order.getId()), suspendedCount(order.getId())));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Views.ClosureDetailView detail(Long orderId) {
        NavigationClosureOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + orderId));

        // 受影响申报：读影响台账（暂停/受阻/恢复），叠加申报实时状态
        List<Views.AffectedPlanView> affected = new ArrayList<>();
        for (ClosurePlanEffect effect : effectRepository.findAllByClosureOrderIdOrderByIdAsc(orderId)) {
            SailingPlan plan = effect.getSailingPlan();
            Vessel vessel = plan.getVessel();
            Long overdue = null;
            if ("APPROVED".equals(plan.getStatus()) && plan.getEtaReturn().isBefore(LocalDateTime.now())) {
                overdue = Duration.between(plan.getEtaReturn(), LocalDateTime.now()).toHours();
            }
            affected.add(new Views.AffectedPlanView(
                    effect.getId(), effect.getEffectType(),
                    MarinaDict.effectTypeName(effect.getEffectType()),
                    effect.getPlanStatusSnap(), effect.getHoldSeq(), effect.getRemark(),
                    effect.getCreatedAt(), effect.getOperator(),
                    plan.getId(), plan.getPlanNo(), vessel.getId(), vessel.getName(),
                    MarinaDict.vesselTypeName(vessel.getTypeCode()),
                    plan.getStatus(), MarinaDict.planStatusName(plan.getStatus()),
                    plan.getDepartTime(), plan.getEtaReturn(), plan.getSkipper(), plan.getPhone(),
                    plan.getRoute(), overdue, plan.getHoldSeq(), plan.getHoldReason()));
        }

        List<Views.DispositionView> dispositions = new ArrayList<>();
        for (ReturnDisposition disp : dispositionRepository.findAllByClosureOrderIdOrderByIdAsc(orderId)) {
            dispositions.add(toDispositionView(disp, false));
        }

        List<Views.ReturnBerthView> berths = new ArrayList<>();
        for (Berth berth : berthRepository.findAllByStatusOrderByBerthNoAsc("FREE")) {
            berths.add(new Views.ReturnBerthView(
                    berth.getId(), berth.getBerthNo(), berth.getZoneCode(),
                    berth.getLengthM(), berth.getMaxDraftM(),
                    MarinaDict.allowTypeNames(berth.getAllowType()), berth.getDailyRate(),
                    berth.getStatus(), MarinaDict.berthStatusName(berth.getStatus())));
        }

        List<Views.ClosureLogView> logs = new ArrayList<>();
        for (ClosureLog log : logRepository.findAllByClosureOrderIdOrderByIdAsc(orderId)) {
            logs.add(new Views.ClosureLogView(log.getId(), log.getAction(), log.getDetail(),
                    log.getOperator(), log.getCreatedAt()));
        }

        return new Views.ClosureDetailView(
                toOrderView(order, counts(orderId), suspendedCount(orderId)),
                affected, dispositions, berths, logs);
    }

    // ============================================================
    // 视图装配 / 小工具
    // ============================================================

    private Map<String, Long> counts(Long orderId) {
        Map<String, Long> c = new HashMap<>();
        for (String s : List.of("TO_CONTACT", "CONFIRMED_RETURN", "LOST_CONTACT", "BERTHED")) {
            c.put(s, dispositionRepository.countByClosureOrderIdAndStatus(orderId, s));
        }
        return c;
    }

    private long suspendedCount(Long orderId) {
        return planRepository.findAllByHoldByOrderIdOrderByHoldSeqAscIdAsc(orderId)
                .stream().filter(p -> "SUSPENDED".equals(p.getStatus())).count();
    }

    private Views.ClosureOrderView toOrderView(NavigationClosureOrder o, Map<String, Long> c, long suspended) {
        long risk = c.getOrDefault("TO_CONTACT", 0L) + c.getOrDefault("CONFIRMED_RETURN", 0L)
                + c.getOrDefault("LOST_CONTACT", 0L);
        long total = c.values().stream().mapToLong(Long::longValue).sum();
        return new Views.ClosureOrderView(
                o.getId(), o.getOrderNo(), o.getReasonType(),
                MarinaDict.closureReasonName(o.getReasonType()), o.getTitle(), o.getDescription(),
                o.getIssuedBy(), o.getStatus(), MarinaDict.closureStatusName(o.getStatus()),
                o.getEffectiveAt(), o.getActivatedAt(), o.getReleasedAt(), o.getArchivedAt(),
                o.getArchivedBy(), o.getReleaseRemark(),
                total, risk,
                c.getOrDefault("TO_CONTACT", 0L), c.getOrDefault("CONFIRMED_RETURN", 0L),
                c.getOrDefault("LOST_CONTACT", 0L), c.getOrDefault("BERTHED", 0L),
                suspended, o.getCreatedAt(), o.getUpdatedAt());
    }

    private Views.DispositionView toDispositionView(ReturnDisposition d, boolean replay) {
        Vessel vessel = d.getVessel();
        SailingPlan plan = d.getSailingPlan();
        Berth planned = d.getPlannedBerth();
        Berth berth = d.getBerth();
        Berthing berthing = d.getBerthingId() == null ? null
                : berthingRepository.findById(d.getBerthingId()).orElse(null);
        return new Views.DispositionView(
                d.getId(), d.getClosureOrder().getId(), vessel.getId(), vessel.getName(),
                vessel.getTypeCode(), MarinaDict.vesselTypeName(vessel.getTypeCode()),
                plan == null ? null : plan.getId(), plan == null ? null : plan.getPlanNo(),
                d.getStatus(), MarinaDict.dispositionStatusName(d.getStatus()),
                MarinaDict.isRiskDisposition(d.getStatus()),
                planned == null ? null : planned.getId(),
                planned == null ? null : planned.getBerthNo(),
                d.getContactedAt(), d.getConfirmedAt(), d.getEscalatedAt(), d.getBerthedAt(),
                berth == null ? null : berth.getId(), berth == null ? null : berth.getBerthNo(),
                d.getBerthingId(), berthing == null ? null : berthing.getRecordNo(),
                d.getCreatedAt(), toContactViews(d.getId()));
    }

    private List<Views.ContactView> toContactViews(Long dispositionId) {
        List<Views.ContactView> list = new ArrayList<>();
        for (DispositionContact c : contactRepository
                .findAllByDispositionIdOrderByContactTimeDescIdDesc(dispositionId)) {
            list.add(new Views.ContactView(c.getId(), c.getContactTime(), c.getResult(),
                    MarinaDict.contactResultName(c.getResult()), c.getRemark(), c.getOperator(),
                    false, c.getCreatedAt()));
        }
        return list;
    }

    private ReturnDisposition reloadDisposition(Long id) {
        entityManager.flush();
        return dispositionRepository.findById(id)
                .orElseThrow(() -> new BizException("返港处置项不存在：id=" + id));
    }

    /** 该船最新一条已批准申报（封航前出海对应那张）。 */
    private SailingPlan latestApprovedPlan(Long vesselId) {
        return planRepository.findAllByVesselIdOrderByDepartTimeDesc(vesselId).stream()
                .filter(p -> "APPROVED".equals(p.getStatus()))
                .findFirst().orElse(null);
    }

    /** 处置状态阶段序：TO_CONTACT=1，CONFIRMED_RETURN / LOST_CONTACT=2，BERTHED=3（防倒退）。 */
    private int rank(String status) {
        return switch (status) {
            case "TO_CONTACT" -> 1;
            case "CONFIRMED_RETURN", "LOST_CONTACT" -> 2;
            case "BERTHED" -> 3;
            default -> 0;
        };
    }

    private void writeLog(NavigationClosureOrder order, String action, String detail, String operator) {
        ClosureLog log = new ClosureLog();
        log.setClosureOrder(order);
        log.setAction(action);
        log.setDetail(detail.length() > 500 ? detail.substring(0, 500) : detail);
        log.setOperator(operator);
        log.setCreatedAt(LocalDateTime.now());
        logRepository.save(log);
    }

    private void writeLog(NavigationClosureOrder order, String action, String detail) {
        writeLog(order, action, detail, order.getIssuedBy());
    }

    private String operator(String value) {
        if (value == null || value.isBlank()) {
            return "值班主管";
        }
        String v = value.trim();
        return v.length() > 32 ? v.substring(0, 32) : v;
    }

    private String nextOrderNo(LocalDateTime at) {
        String prefix = "FH-" + at.format(NO_SUFFIX) + "-";
        long seq = orderRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (orderRepository.existsByOrderNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }

    private String nextBerthingNo(LocalDateTime at) {
        String prefix = "KP-" + at.format(NO_SUFFIX) + "-";
        long seq = berthingRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (berthingRepository.existsByRecordNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }
}
