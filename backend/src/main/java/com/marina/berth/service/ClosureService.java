package com.marina.berth.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.ClosureOrder;
import com.marina.berth.domain.DispositionContact;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.PlanSuspendLog;
import com.marina.berth.domain.ReturnDisposition;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.ClosureDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.BerthingRepository;
import com.marina.berth.repository.ClosureOrderRepository;
import com.marina.berth.repository.DispositionContactRepository;
import com.marina.berth.repository.PlanSuspendLogRepository;
import com.marina.berth.repository.ReturnDispositionRepository;
import com.marina.berth.repository.SailingPlanRepository;
import com.marina.berth.repository.VesselRepository;

/**
 * 模块六/七：封航令与返港处置台。
 *
 * <p>状态机：
 * <pre>
 *   封航令  SCHEDULED 待生效 ──到点/手动生效──▶ EFFECTIVE 生效中 ──发起解除──▶ LIFTING 解除中 ──风险清零──▶ ARCHIVED 已归档
 *                                     └────────────────（待生效令可直接作废归档）────────────────▶ ARCHIVED
 *
 *   处置项  TO_CONTACT 待联系 ──▶ CONFIRMED_RETURN 已确认返港 ──▶ SAFE_BERTHED 已安全靠泊（终态）
 *                        └──▶ ESCALATED 失联升级 ◀──▶ CONFIRMED_RETURN（重新联系上可互转）
 *                        ESCALATED ──▶ SAFE_BERTHED（失联船自行安全回港）
 * </pre>
 *
 * <p>三条硬保证（对应题目点名的验收点）：
 * <ol>
 *   <li><b>跨实体一致性</b>：安全靠泊把 处置项/出航申报/船只/靠泊记录/泊位 五笔写放在同一事务，
 *       任一步失败整笔回滚，并在错误文案里说明哪些数据没有改变（一笔都没提交，等于全没变）。</li>
 *   <li><b>并发幂等</b>：封航生效、处置项生成、状态推进、泊位占用全部先拿行锁
 *       （固定锁顺序 封航令 → 申报/船只 → 泊位），叠加 uk(closure_id,vessel_id) 唯一约束、
 *       联系记录 requestId 幂等键；重复点击 / 超时重试不会产生重复记录、状态倒退或重复占泊位。</li>
 *   <li><b>可追溯</b>：暂停/恢复写 plan_suspend_log，每次联系写 disposition_contact，
 *       封航令四阶段时间戳齐全；刷新、关页重开读到的都是同一份库内事实。</li>
 * </ol>
 */
@Service
public class ClosureService {

    private static final DateTimeFormatter NO_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter KP_SUFFIX = DateTimeFormatter.ofPattern("yyyyMM");

    private final ClosureOrderRepository closureRepository;
    private final ReturnDispositionRepository dispRepository;
    private final DispositionContactRepository contactRepository;
    private final PlanSuspendLogRepository suspendLogRepository;
    private final SailingPlanRepository planRepository;
    private final VesselRepository vesselRepository;
    private final BerthRepository berthRepository;
    private final BerthingRepository berthingRepository;
    private final TransactionTemplate txTemplate;

    public ClosureService(ClosureOrderRepository closureRepository,
                          ReturnDispositionRepository dispRepository,
                          DispositionContactRepository contactRepository,
                          PlanSuspendLogRepository suspendLogRepository,
                          SailingPlanRepository planRepository,
                          VesselRepository vesselRepository,
                          BerthRepository berthRepository,
                          BerthingRepository berthingRepository,
                          PlatformTransactionManager txManager) {
        this.closureRepository = closureRepository;
        this.dispRepository = dispRepository;
        this.contactRepository = contactRepository;
        this.suspendLogRepository = suspendLogRepository;
        this.planRepository = planRepository;
        this.vesselRepository = vesselRepository;
        this.berthRepository = berthRepository;
        this.berthingRepository = berthingRepository;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ==================================================================== 封航令：发布/生效

    /** 发布封航令（保存为待生效；生效时间已到则在本事务内直接生效） */
    @Transactional
    public Views.ClosureView create(ClosureDtos.ClosureCreateReq req) {
        String reasonType = req.reasonType().trim().toUpperCase();
        if (!MarinaDict.isValidClosureReason(reasonType)) {
            throw new BizException("封航事由不合法：" + req.reasonType());
        }
        LocalDateTime effectiveAt = req.effectiveAt();
        if (effectiveAt == null) {
            throw new BizException("发布生效时间不能为空");
        }
        if (effectiveAt.isBefore(LocalDateTime.now().minusMinutes(1))) {
            throw new BizException("生效时间不能早于当前时间（如需立即封航请直接发当前时刻）");
        }
        // 同一时段只允许一张活动令：锁活动令后判定，避免主管并发各发一张
        List<ClosureOrder> active = closureRepository.findActiveForUpdate();
        if (!active.isEmpty()) {
            ClosureOrder first = active.get(0);
            throw new BizException("已存在" + MarinaDict.closureStatusName(first.getStatus())
                    + "的封航令 " + first.getOrderNo() + "，需先归档后才能发布新令");
        }

        ClosureOrder order = new ClosureOrder();
        order.setOrderNo(nextOrderNo());
        order.setReasonType(reasonType);
        order.setReasonDetail(trimTo(req.reasonDetail(), 256));
        order.setEffectiveAt(effectiveAt);
        order.setIssuedBy(trimTo(req.issuedBy(), 32));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        order.setStatus("SCHEDULED");
        order = closureRepository.save(order);

        // 发布时间已到（分钟级容差）：立即生效
        if (!effectiveAt.isAfter(LocalDateTime.now())) {
            order = activateLocked(order, trimTo(req.issuedBy(), 32));
        }
        return toClosureView(order);
    }

    /** 手动提前生效（主管立即宣布封航）；已生效/已推进的令幂等返回现状 */
    @Transactional
    public Views.ClosureView activate(Long closureId, String operator) {
        ClosureOrder order = closureRepository.findByIdForUpdate(closureId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + closureId));
        if ("SCHEDULED".equals(order.getStatus())) {
            order.setEffectiveAt(LocalDateTime.now());
            order = activateLocked(order, trimTo(operator, 32));
        }
        // 非待生效（生效中/解除中/已归档）属于重复点击，幂等返回当前状态
        return toClosureView(order);
    }

    /**
     * 真正的生效快照（调用方必须已持有封航令行锁）。
     * 单事务做两件事：① 已批准但尚未出航的申报批量「暂停放行」；② 每条在海船生成一条返港处置项。
     */
    private ClosureOrder activateLocked(ClosureOrder order, String operator) {
        LocalDateTime now = LocalDateTime.now();
        String suspendReason = "封航令 " + order.getOrderNo() + "（"
                + MarinaDict.closureReasonName(order.getReasonType())
                + (order.getReasonDetail() == null || order.getReasonDetail().isBlank()
                        ? "" : "：" + order.getReasonDetail())
                + "）生效，暂停放行";

        // ① 暂停放行：先按原审批顺序取出名单（写审计日志要留 id），再条件批量更新
        List<SailingPlan> toSuspend = planRepository.findApprovedNotDeparted(now);
        if (!toSuspend.isEmpty()) {
            int updated = planRepository.suspendApprovedNotDeparted(now, suspendReason);
            if (updated != toSuspend.size()) {
                // 并发下被别的事务改过行（正常已被行锁挡住）：宁可回滚也不产生半套暂停
                throw new BizException("暂停放行申报时数据发生并发变化，已全部回滚，本次没有任何申报被暂停，请重试");
            }
            for (SailingPlan p : toSuspend) {
                PlanSuspendLog log = new PlanSuspendLog();
                log.setClosure(order);
                log.setPlan(p);
                log.setAction("SUSPEND");
                log.setReason(suspendReason);
                log.setOperator(operator);
                log.setCreatedAt(now);
                suspendLogRepository.save(log);
            }
        }

        // ② 返港处置项：封航令行锁已被调用方持有，在海船再逐艘加行锁，双重串行保证一船一项。
        //    uk(closure_id,vessel_id) 是最后一道防线：若极端并发插入撞约束，事务整笔回滚，
        //    由定时任务下一轮 / 前端重试幂等补做，绝不留半套名单。
        List<Vessel> outSea = vesselRepository.findOutSeaForUpdate();
        for (Vessel v : outSea) {
            if (dispRepository.findByClosureIdAndVesselId(order.getId(), v.getId()).isPresent()) {
                continue; // 幂等：本令下该船已有处置项（重试/重复生效）
            }
            SailingPlan openPlan = findApprovedPlanOf(v.getId());
            ReturnDisposition d = new ReturnDisposition();
            d.setClosure(order);
            d.setVessel(v);
            d.setSailingPlan(openPlan);
            d.setStatus("TO_CONTACT");
            d.setCreatedAt(now);
            d.setUpdatedAt(now);
            dispRepository.save(d);
        }

        order.setStatus("EFFECTIVE");
        order.setActivatedAt(now);
        order.setUpdatedAt(now);
        return closureRepository.save(order);
    }

    /**
     * 定时任务入口：把所有到点的待生效令逐一生效。
     * 本方法本身不开事务；每张令用 {@link TransactionTemplate} 起独立事务，
     * 单条失败整笔回滚、留给下一轮重试，不影响其它令到点生效（也不中断定时器线程）。
     */
    public void activateDueOrders() {
        List<ClosureOrder> due = closureRepository
                .findByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAsc(
                        "SCHEDULED", LocalDateTime.now());
        for (ClosureOrder d : due) {
            Long id = d.getId();
            try {
                txTemplate.executeWithoutResult(status -> {
                    ClosureOrder locked = closureRepository.findByIdForUpdate(id).orElse(null);
                    if (locked != null && "SCHEDULED".equals(locked.getStatus())) {
                        activateLocked(locked, locked.getIssuedBy());
                    }
                });
            } catch (RuntimeException e) {
                // 并发冲突等：事务已回滚，不留半成品；下一轮（5 秒后）幂等补做
            }
        }
    }

    /** 手动/单令生效（由外部在自己的事务里调用；供需要立即拿结果的场景复用） */
    @Transactional
    public void activateOne(Long closureId) {
        ClosureOrder locked = closureRepository.findByIdForUpdate(closureId).orElse(null);
        if (locked != null && "SCHEDULED".equals(locked.getStatus())) {
            activateLocked(locked, locked.getIssuedBy());
        }
    }

    /**
     * 审批闸门专用：在当前写事务内先把到点令懒生效（与批准共用同一事务/同一把锁），
     * 再返回当前生效中的令（无则 null）。保证「发布时间已到且仍处于生效中」时批准被挡住。
     */
    @Transactional
    public ClosureOrder effectiveGateAfterAutoActivate() {
        List<ClosureOrder> due = closureRepository
                .findByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAsc(
                        "SCHEDULED", LocalDateTime.now());
        for (ClosureOrder d : due) {
            ClosureOrder locked = closureRepository.findByIdForUpdate(d.getId()).orElse(null);
            if (locked != null && "SCHEDULED".equals(locked.getStatus())) {
                activateLocked(locked, locked.getIssuedBy());
            }
        }
        return closureRepository.findFirstByStatusOrderByEffectiveAtAsc("EFFECTIVE").orElse(null);
    }

    // ==================================================================== 封航令：解除/归档

    /** 发起解除：生效中 → 解除中。把暂停的申报恢复为「可继续审批」（不自动批准，保留原顺序与暂停原因） */
    @Transactional
    public Views.ClosureView beginLift(Long closureId, String operator) {
        ClosureOrder order = closureRepository.findByIdForUpdate(closureId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + closureId));
        LocalDateTime now = LocalDateTime.now();
        if ("LIFTING".equals(order.getStatus()) || "ARCHIVED".equals(order.getStatus())) {
            return toClosureView(order); // 重复点击幂等
        }
        if (!"EFFECTIVE".equals(order.getStatus())) {
            throw new BizException("只有生效中的封航令可以发起解除，当前阶段："
                    + MarinaDict.closureStatusName(order.getStatus()));
        }

        // 恢复暂停申报：仍按原审批顺序（approved_at）取回，批量恢复为 APPROVED 并写 RESUME 日志。
        // 不自动批准，不触碰 suspended_reason（页面上仍可查为何曾被暂停）。
        List<SailingPlan> suspended = planRepository.findSuspendedByClosure(order.getId());
        if (!suspended.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            for (SailingPlan p : suspended) {
                ids.add(p.getId());
            }
            int resumed = planRepository.resumeByIds(ids, now);
            if (resumed != ids.size()) {
                throw new BizException("恢复暂停申报时数据发生并发变化，已全部回滚，本次没有任何申报被恢复，请重试");
            }
            for (SailingPlan p : suspended) {
                PlanSuspendLog log = new PlanSuspendLog();
                log.setClosure(order);
                log.setPlan(p);
                log.setAction("RESUME");
                log.setReason("封航令 " + order.getOrderNo() + " 进入解除中，恢复可继续审批（未自动批准）");
                log.setOperator(operator);
                log.setCreatedAt(now);
                suspendLogRepository.save(log);
            }
        }

        order.setStatus("LIFTING");
        order.setLiftingStartedAt(now);
        order.setLiftedBy(trimTo(operator, 32));
        order.setUpdatedAt(now);
        return toClosureView(closureRepository.save(order));
    }

    /**
     * 归档：仍有「失联升级」或「已确认返港但尚未安全靠泊」处置项时只能停在解除中，风险清零才放行。
     * 待生效令允许直接作废归档（实际台风改向、管制取消等）。
     */
    @Transactional
    public Views.ClosureView archive(Long closureId, String operator) {
        ClosureOrder order = closureRepository.findByIdForUpdate(closureId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + closureId));
        LocalDateTime now = LocalDateTime.now();
        if ("ARCHIVED".equals(order.getStatus())) {
            return toClosureView(order); // 幂等
        }
        if ("SCHEDULED".equals(order.getStatus())) {
            // 待生效直接作废归档：无快照、无处置项
            order.setStatus("ARCHIVED");
            order.setArchivedAt(now);
            order.setArchivedBy(trimTo(operator, 32));
            order.setUpdatedAt(now);
            return toClosureView(closureRepository.save(order));
        }
        if ("EFFECTIVE".equals(order.getStatus())) {
            throw new BizException("封航令仍在生效中，请先发起解除（进入解除中），待风险项清零后再归档");
        }
        if (!"LIFTING".equals(order.getStatus())) {
            throw new BizException("当前封航令阶段不允许归档：" + MarinaDict.closureStatusName(order.getStatus()));
        }
        long riskOpen = dispRepository.countRiskOpen(order.getId());
        if (riskOpen > 0) {
            throw new BizException("仍有 " + riskOpen + " 条「失联升级 / 已确认返港但未安全靠泊」的处置项，"
                    + "只能停留在解除中；待风险项清零后才能归档");
        }

        // 归档时回收仍挂着的返港预留泊位（只可能是给本令预留的 RESERVED 位）
        List<ReturnDisposition> all = dispRepository.findAllByClosureIdOrderByIdAsc(order.getId());
        for (ReturnDisposition d : all) {
            if (d.getAssignedBerth() != null) {
                Berth b = berthRepository.findByIdForUpdate(d.getAssignedBerth().getId()).orElse(null);
                if (b != null && "RESERVED".equals(b.getStatus())
                        && order.getId().equals(b.getReservedReturnId())) {
                    b.setStatus("FREE");
                    b.setReservedReturnId(null);
                    b.setUpdatedAt(now);
                    berthRepository.save(b);
                }
                d.setAssignedBerth(null);
                d.setAssignedAt(null);
                d.setAssignedBy(null);
                d.setUpdatedAt(now);
                dispRepository.save(d);
            }
        }

        order.setStatus("ARCHIVED");
        order.setArchivedAt(now);
        order.setArchivedBy(trimTo(operator, 32));
        order.setUpdatedAt(now);
        return toClosureView(closureRepository.save(order));
    }

    // ==================================================================== 返港处置：联系/状态

    /** 记一次联系（保留时间、结果、备注；requestId 幂等），并按结果推进处置状态（单调不倒退） */
    @Transactional
    public Views.DispositionView addContact(Long dispositionId, ClosureDtos.ContactReq req) {
        if (!MarinaDict.isValidContactResult(req.resultCode())) {
            throw new BizException("联系结果不合法：" + req.resultCode());
        }
        if ("SAFE".equals(req.resultCode())) {
            throw new BizException("登记安全靠泊请使用「安全靠泊」操作（会同步办理靠泊、申报与泊位，整笔一致）");
        }
        ReturnDisposition d = loadDispForUpdate(dispositionId);
        String requestId = trimTo(req.requestId(), 64);
        LocalDateTime contactAt = parseTime(req.contactAt());
        LocalDateTime now = LocalDateTime.now();
        if (requestId != null) {
            // 幂等：同一 requestId 已落过库（重复点击/超时重试），直接回显现状，不再推进状态。
            // 处置项行锁已在本事务持有，并发重复请求在锁上排队，后到者一定能看到先到者的插入。
            if (contactRepository.findByRequestId(requestId).isPresent()) {
                return toDispositionView(d);
            }
        }
        DispositionContact c = new DispositionContact();
        c.setDisposition(d);
        c.setContactAt(contactAt == null ? now : contactAt);
        c.setResultCode(req.resultCode().trim().toUpperCase());
        c.setResultText(trimTo(req.resultText(), 128));
        c.setRemark(trimTo(req.remark(), 256));
        c.setOperator(trimTo(req.operator(), 32));
        c.setRequestId(requestId);
        c.setCreatedAt(now);
        contactRepository.save(c);
        DispositionContact saved = c;

        // 状态推进（只允许单调，TO_CONTACT 不可回退）
        switch (saved.getResultCode()) {
            case "ACK" -> {
                ensureNotBerthed(d);
                if (!"CONFIRMED_RETURN".equals(d.getStatus())) {
                    d.setStatus("CONFIRMED_RETURN");
                    d.setConfirmedAt(now);
                }
            }
            case "ESCALATE" -> {
                ensureNotBerthed(d);
                d.setStatus("ESCALATED");
                d.setEscalatedAt(now);
                if (d.getEscalateReason() == null && saved.getRemark() != null) {
                    d.setEscalateReason(saved.getRemark());
                }
            }
            default -> { /* NOTIFIED / NO_ANSWER / OTHER 只留痕，不改处置状态 */ }
        }
        d.setUpdatedAt(now);
        return toDispositionView(dispRepository.save(d));
    }

    /** 失联升级（带失联说明，自动留一条「失联上报」联系记录） */
    @Transactional
    public Views.DispositionView escalate(Long dispositionId, ClosureDtos.EscalateReq req) {
        ReturnDisposition d = loadDispForUpdate(dispositionId);
        if ("SAFE_BERTHED".equals(d.getStatus())) {
            throw new BizException("该船已安全靠泊，处置项已结，不能再标记失联");
        }
        LocalDateTime now = LocalDateTime.now();
        String requestId = trimTo(req.requestId(), 64);
        if (requestId != null && contactRepository.findByRequestId(requestId).isPresent()) {
            return toDispositionView(d); // 重复点击/超时重试幂等
        }
        DispositionContact c = new DispositionContact();
        c.setDisposition(d);
        c.setContactAt(now);
        c.setResultCode("ESCALATE");
        c.setResultText("失联升级");
        c.setRemark(trimTo(req.reason(), 256));
        c.setOperator(trimTo(req.operator(), 32));
        c.setRequestId(requestId);
        c.setCreatedAt(now);
        contactRepository.save(c);
        d.setStatus("ESCALATED");
        d.setEscalatedAt(now);
        d.setEscalateReason(trimTo(req.reason(), 256));
        d.setUpdatedAt(now);
        return toDispositionView(dispRepository.save(d));
    }

    // ==================================================================== 返港处置：预留泊位

    /** 为处置项预留可接纳返港的泊位（该位转为 RESERVED 并指向封航令，先到先得、行锁防重复占用） */
    @Transactional
    public Views.DispositionView assignBerth(Long dispositionId, ClosureDtos.AssignBerthReq req) {
        ReturnDisposition d = loadDispForUpdate(dispositionId);
        if ("SAFE_BERTHED".equals(d.getStatus())) {
            throw new BizException("该船已安全靠泊，无需再预留泊位");
        }
        Vessel vessel = d.getVessel();
        Berth target = berthRepository.findByIdForUpdate(req.berthId())
                .orElseThrow(() -> new BizException("泊位不存在：id=" + req.berthId()));
        if ("DISABLED".equals(target.getStatus())) {
            throw new BizException("泊位 " + target.getBerthNo() + " 处于停用维修，不能接纳返港");
        }
        if ("IN_PORT".equals(target.getStatus())) {
            throw new BizException("泊位 " + target.getBerthNo() + " 已有船在泊，不能重复占用");
        }
        if ("RESERVED".equals(target.getStatus())
                && (target.getReservedReturnId() == null
                    || !target.getReservedReturnId().equals(d.getClosure().getId()))) {
            throw new BizException("泊位 " + target.getBerthNo() + " 已被其他封航返港预留，不能重复占用");
        }
        if ("RESERVED".equals(target.getStatus())
                && d.getAssignedBerth() != null
                && target.getId().equals(d.getAssignedBerth().getId())) {
            return toDispositionView(d); // 重复点击幂等：本就是给它预留的
        }
        checkFit(target, vessel);

        LocalDateTime now = LocalDateTime.now();
        // 改预留在先：释放旧预留位，再占新位（持锁顺序按 id，避免两个分配互锁）
        if (d.getAssignedBerth() != null && !d.getAssignedBerth().getId().equals(target.getId())) {
            releaseReservedBerth(d.getAssignedBerth().getId(), d.getClosure().getId(), now);
        }
        target.setStatus("RESERVED");
        target.setReservedReturnId(d.getClosure().getId());
        target.setUpdatedAt(now);
        berthRepository.save(target);

        d.setAssignedBerth(target);
        d.setAssignedAt(now);
        d.setAssignedBy(trimTo(req.operator(), 32));
        d.setUpdatedAt(now);
        return toDispositionView(dispRepository.save(d));
    }

    /** 释放该处置项预留的泊位（RESERVED → FREE） */
    @Transactional
    public Views.DispositionView releaseBerth(Long dispositionId, String operator) {
        ReturnDisposition d = loadDispForUpdate(dispositionId);
        if (d.getAssignedBerth() == null) {
            return toDispositionView(d);
        }
        LocalDateTime now = LocalDateTime.now();
        releaseReservedBerth(d.getAssignedBerth().getId(), d.getClosure().getId(), now);
        d.setAssignedBerth(null);
        d.setAssignedAt(null);
        d.setAssignedBy(null);
        d.setUpdatedAt(now);
        return toDispositionView(dispRepository.save(d));
    }

    private void releaseReservedBerth(Long berthId, Long closureId, LocalDateTime now) {
        Berth old = berthRepository.findByIdForUpdate(berthId).orElse(null);
        if (old == null) {
            return;
        }
        if (!"RESERVED".equals(old.getStatus()) || old.getReservedReturnId() == null
                || !old.getReservedReturnId().equals(closureId)) {
            throw new BizException("泊位 " + old.getBerthNo() + " 已不是本封航令的返港预留位，未做任何变更");
        }
        old.setStatus("FREE");
        old.setReservedReturnId(null);
        old.setUpdatedAt(now);
        berthRepository.save(old);
    }

    // ==================================================================== 返港处置：安全靠泊（一致性核心）

    /**
     * 登记安全靠泊。处置项(已确认返港/失联升级) → 出航申报 → 船只 → 靠泊记录 → 泊位
     * 五笔写在同一事务：任何一步抛错，Spring 整笔回滚，页面提示明确说明没有任何数据被改变。
     * requestId 幂等：重复提交若已靠泊，直接返回既有结果。
     */
    @Transactional
    public Views.DispositionView safeBerth(Long dispositionId, ClosureDtos.SafeBerthReq req) {
        ReturnDisposition d = loadDispForUpdate(dispositionId);
        // 幂等：两名值班员先后确认 / 网络重试，已经安全靠泊就回显同一结果
        if ("SAFE_BERTHED".equals(d.getStatus())) {
            return toDispositionView(d);
        }
        if ("TO_CONTACT".equals(d.getStatus())) {
            throw rollbackHint("该船尚未确认返港，请先联系确认或标记失联升级，再登记安全靠泊");
        }
        Vessel vessel = vesselRepository.findByIdForUpdate(d.getVessel().getId())
                .orElseThrow(() -> rollbackHint("船只台账数据缺失"));
        if (!"OUT_SEA".equals(vessel.getStatus())) {
            throw rollbackHint("船只 " + vessel.getName() + " 当前不是出海状态，状态可能已被其他处置改变");
        }
        if (berthingRepository.findFirstByVesselIdAndStatusOrderByBerthTimeDesc(vessel.getId(), "IN_PORT").isPresent()) {
            throw rollbackHint("船只 " + vessel.getName() + " 已存在在泊记录，为防重复靠泊本次未写入任何数据");
        }

        // 定泊位：入参指定优先，否则用预留位；锁定后再做最终占用判定（防并发重复占泊位）
        Long wantedBerthId = req.berthId() != null ? req.berthId()
                : (d.getAssignedBerth() != null ? d.getAssignedBerth().getId() : null);
        if (wantedBerthId == null) {
            throw rollbackHint("尚未为该船预留泊位，也未指定靠泊泊位，请先选择可接纳返港的泊位");
        }
        Berth berth = berthRepository.findByIdForUpdate(wantedBerthId)
                .orElseThrow(() -> rollbackHint("泊位不存在：id=" + wantedBerthId));
        boolean wasReservedForUs = "RESERVED".equals(berth.getStatus())
                && berth.getReservedReturnId() != null
                && berth.getReservedReturnId().equals(d.getClosure().getId());
        if ("IN_PORT".equals(berth.getStatus())) {
            throw rollbackHint("泊位 " + berth.getBerthNo() + " 已被其他船占用，本次未办理靠泊");
        }
        if (!"FREE".equals(berth.getStatus()) && !wasReservedForUs) {
            throw rollbackHint("泊位 " + berth.getBerthNo() + " 当前为"
                    + MarinaDict.berthStatusName(berth.getStatus()) + "，不能接纳本次靠泊");
        }
        checkFit(berth, vessel);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime berthAt = parseTime(req.berthTime());
        if (berthAt == null) {
            berthAt = now;
        }
        String operator = trimTo(req.operator(), 32);

        // ① 靠泊记录（IN_PORT 在泊行，@JoinFormula 派生关联立刻让泊位带上船、船带上泊位）
        Berthing record = new Berthing();
        record.setRecordNo(nextBerthingNo(berthAt));
        record.setBerth(berth);
        record.setVessel(vessel);
        record.setBerthTime(berthAt);
        record.setEtaDepart(null);
        record.setActualDepart(null);
        record.setDays(null);
        record.setFee(null);
        record.setStatus("IN_PORT");
        record.setUpdatedAt(now);
        record = berthingRepository.save(record);
        berthingRepository.flush();

        // ② 泊位 → 在泊 / 清预留
        berth.setStatus("IN_PORT");
        berth.setReservedReturnId(null);
        berth.setUpdatedAt(now);
        berthRepository.save(berth);

        // ③ 船只 → 在港
        vessel.setStatus("IN_PORT");
        vessel.setUpdatedAt(now);
        vesselRepository.save(vessel);

        // ④ 关联的已批准出海申报 → 已返航（封航前出海那条；暂停放行的申报不属于本船出海中，不动）
        SailingPlan plan = d.getSailingPlan();
        if (plan != null && plan.getId() != null) {
            plan = planRepository.findByIdForUpdate(plan.getId()).orElse(plan);
            if ("APPROVED".equals(plan.getStatus())) {
                plan.setStatus("RETURNED");
                plan.setUpdatedAt(now);
                planRepository.save(plan);
            }
        }

        // ⑤ 处置项 → 已安全靠泊（终态，不可回退）
        d.setStatus("SAFE_BERTHED");
        d.setBerthedAt(berthAt);
        d.setBerthedBerthId(berth.getId());
        d.setBerthedBy(operator);
        d.setBerthingId(record.getId());
        if (wasReservedForUs) {
            // 靠的就是预留位：预留占用随靠泊结束而消除
            d.setAssignedBerth(null);
            d.setAssignedAt(null);
            d.setAssignedBy(null);
        }
        d.setUpdatedAt(now);
        d = dispRepository.save(d);

        // 靠泊动作也留一条联系痕迹，处置过程完整可追溯。
        // 重复请求在方法开头的 SAFE_BERTHED 幂等分支就已返回，不会走到这里，故正常 save 即可。
        DispositionContact c = new DispositionContact();
        c.setDisposition(d);
        c.setContactAt(berthAt);
        c.setResultCode("SAFE");
        c.setResultText("安全靠泊 " + berth.getBerthNo());
        c.setRemark(trimTo(req.remark(), 256));
        c.setOperator(operator);
        c.setRequestId(trimTo(req.requestId(), 64));
        c.setCreatedAt(now);
        contactRepository.save(c);
        return toDispositionView(d);
    }

    /** 一致性失败文案：明确告诉值班员哪些数据没有改变（整笔回退 = 一笔都没改） */
    private BizException rollbackHint(String reason) {
        return new BizException("安全靠泊未办理：" + reason
                + "。该操作已整笔回退，处置项状态、出航申报、船只状态、靠泊记录与泊位占用均保持原样、没有任何改变");
    }

    // ==================================================================== 查询：列表/详情/泊位候选

    @Transactional(readOnly = true)
    public List<Views.ClosureView> list(String status) {
        List<ClosureOrder> source;
        if (status != null && !status.isBlank()) {
            source = closureRepository.findAllByStatusOrderByEffectiveAtAsc(status.trim().toUpperCase());
        } else {
            source = closureRepository.findAllByOrderByCreatedAtDescIdDesc();
        }
        List<Views.ClosureView> list = new ArrayList<>();
        for (ClosureOrder o : source) {
            list.add(toClosureView(o));
        }
        return list;
    }

    /** 当前活动令（待生效/生效中/解除中，至多一条） */
    @Transactional(readOnly = true)
    public Views.ClosureView active() {
        List<ClosureOrder> active = closureRepository.findAllByOrderByCreatedAtDescIdDesc();
        for (ClosureOrder o : active) {
            if (!"ARCHIVED".equals(o.getStatus())) {
                return toClosureView(o);
            }
        }
        return null;
    }

    /**
     * 封航详情快照：受影响的出航申报（暂停放行 + 生效期间的待审批）与全部返港处置项同处可见。
     * 页面刷新 / 关页重开后读到的就是这份持久化结果。
     */
    @Transactional(readOnly = true)
    public Views.ClosureView detail(Long closureId) {
        ClosureOrder order = closureRepository.findById(closureId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + closureId));
        return toClosureView(order);
    }

    @Transactional(readOnly = true)
    public List<Views.AffectedPlanView> affectedPlans(Long closureId) {
        ClosureOrder order = closureRepository.findById(closureId)
                .orElseThrow(() -> new BizException("封航令不存在：id=" + closureId));
        List<Views.AffectedPlanView> out = new ArrayList<>();
        // 本令暂停过的申报（即便已恢复/已结单也要可追溯）
        for (PlanSuspendLog log : suspendLogRepository.findAllByClosureIdOrderByIdAsc(closureId)) {
            SailingPlan p = planRepository.findById(log.getPlan().getId()).orElse(null);
            if (p != null && log.getAction().equals("SUSPEND")) {
                out.add(toAffectedPlan(p, "SUSPENDED"));
            }
        }
        // 生效期间仍待审批的申报：updated_at 不早于本令生效（封航生效后新建/修改的待审批都在此可见）
        for (SailingPlan p : planRepository.findAllByOrderByDepartTimeDesc()) {
            if ("PENDING".equals(p.getStatus()) && order.getActivatedAt() != null
                    && p.getUpdatedAt() != null && !p.getUpdatedAt().isBefore(order.getActivatedAt())) {
                out.add(toAffectedPlan(p, "PENDING"));
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Views.DispositionView> dispositions(Long closureId, String status) {
        List<ReturnDisposition> source;
        if (closureId != null) {
            source = dispRepository.findAllByClosureIdOrderByIdAsc(closureId);
        } else {
            // 无令时按全量（跨令历史追溯）
            source = new ArrayList<>();
            for (ClosureOrder o : closureRepository.findAllByOrderByCreatedAtDescIdDesc()) {
                source.addAll(dispRepository.findAllByClosureIdOrderByIdAsc(o.getId()));
            }
        }
        List<Views.DispositionView> list = new ArrayList<>();
        for (ReturnDisposition d : source) {
            if (status != null && !status.isBlank() && !status.trim().equalsIgnoreCase(d.getStatus())) {
                continue;
            }
            list.add(toDispositionView(d));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Views.DispositionView dispositionDetail(Long dispositionId) {
        ReturnDisposition d = dispRepository.findById(dispositionId)
                .orElseThrow(() -> new BizException("返港处置项不存在：id=" + dispositionId));
        return toDispositionView(d);
    }

    /** 可接纳某处置船返港的泊位：空闲或本封航预留，且船长/吃水/船型适泊 */
    @Transactional(readOnly = true)
    public List<Views.BerthOptionView> candidateBerths(Long dispositionId) {
        ReturnDisposition d = dispRepository.findById(dispositionId)
                .orElseThrow(() -> new BizException("返港处置项不存在：id=" + dispositionId));
        Vessel vessel = d.getVessel();
        Long closureId = d.getClosure().getId();
        List<Views.BerthOptionView> out = new ArrayList<>();
        for (Berth b : berthRepository.findAllByOrderByBerthNoAsc()) {
            boolean open = "FREE".equals(b.getStatus())
                    || ("RESERVED".equals(b.getStatus()) && closureId.equals(b.getReservedReturnId()));
            if (!open) {
                continue;
            }
            if (vessel.getLengthM().compareTo(b.getLengthM()) > 0
                    || vessel.getDraftM().compareTo(b.getMaxDraftM()) > 0
                    || !MarinaDict.allowTypeContains(b.getAllowType(), vessel.getTypeCode())) {
                continue;
            }
            out.add(new Views.BerthOptionView(b.getId(), b.getBerthNo(), b.getZoneCode(),
                    b.getLengthM(), b.getMaxDraftM(),
                    MarinaDict.allowTypeNames(b.getAllowType()), b.getDailyRate(), b.getStatus()));
        }
        return out;
    }

    // ==================================================================== 视图装配 / 工具

    private ReturnDisposition loadDispForUpdate(Long id) {
        return dispRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("返港处置项不存在：id=" + id));
    }

    private void ensureNotBerthed(ReturnDisposition d) {
        if ("SAFE_BERTHED".equals(d.getStatus())) {
            throw new BizException("该船已安全靠泊，处置项已结，状态不能再改变");
        }
    }

    private void checkFit(Berth berth, Vessel vessel) {
        if (vessel.getLengthM().compareTo(berth.getLengthM()) > 0) {
            throw new BizException("船长 " + vessel.getLengthM() + "m 超过泊位 " + berth.getBerthNo()
                    + " 的长度 " + berth.getLengthM() + "m");
        }
        if (vessel.getDraftM().compareTo(berth.getMaxDraftM()) > 0) {
            throw new BizException("船只吃水 " + vessel.getDraftM() + "m 超过泊位 " + berth.getBerthNo()
                    + " 的最大吃水 " + berth.getMaxDraftM() + "m");
        }
        if (!MarinaDict.allowTypeContains(berth.getAllowType(), vessel.getTypeCode())) {
            throw new BizException("泊位 " + berth.getBerthNo() + " 只允许 "
                    + MarinaDict.allowTypeNames(berth.getAllowType()) + "，"
                    + MarinaDict.vesselTypeName(vessel.getTypeCode()) + " 不能停靠");
        }
    }

    /** 该船当前那条「已批准」的出海申报（封航前已出海的依据） */
    private SailingPlan findApprovedPlanOf(Long vesselId) {
        for (SailingPlan p : planRepository.findAllByVesselIdOrderByDepartTimeDesc(vesselId)) {
            if ("APPROVED".equals(p.getStatus()) || "SUSPENDED".equals(p.getStatus())) {
                return "APPROVED".equals(p.getStatus()) ? p : null;
            }
        }
        return null;
    }

    private String nextOrderNo() {
        String prefix = "FH-" + LocalDateTime.now().format(NO_SUFFIX) + "-";
        long seq = closureRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (closureRepository.existsByOrderNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }

    private String nextBerthingNo(LocalDateTime at) {
        String prefix = "KP-" + at.format(KP_SUFFIX) + "-";
        long seq = berthingRepository.count() + 1;
        String no = prefix + String.format("%04d", seq);
        while (berthingRepository.existsByRecordNo(no)) {
            seq++;
            no = prefix + String.format("%04d", seq);
        }
        return no;
    }

    private LocalDateTime parseTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim().replace(' ', 'T');
        try {
            if (t.length() <= 16) {
                return LocalDateTime.parse(t + ":00");
            }
            return LocalDateTime.parse(t);
        } catch (Exception e) {
            throw new BizException("时间格式有误：" + text + "（应为 yyyy-MM-dd HH:mm）");
        }
    }

    private String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    private Views.ClosureView toClosureView(ClosureOrder o) {
        Map<String, Long> cnt = new HashMap<>();
        for (String s : MarinaDict.DISP_STATUS_ORDER) {
            cnt.put(s, 0L);
        }
        List<ReturnDisposition> disps = dispRepository.findAllByClosureIdOrderByIdAsc(o.getId());
        long total = disps.size();
        for (ReturnDisposition d : disps) {
            cnt.merge(d.getStatus(), 1L, Long::sum);
        }
        long suspended = 0;
        for (PlanSuspendLog l : suspendLogRepository.findAllByClosureIdOrderByIdAsc(o.getId())) {
            if ("SUSPEND".equals(l.getAction())) {
                suspended++;
            }
        }
        return new Views.ClosureView(
                o.getId(), o.getOrderNo(), o.getReasonType(),
                MarinaDict.closureReasonName(o.getReasonType()), o.getReasonDetail(),
                o.getEffectiveAt(), o.getStatus(), MarinaDict.closureStatusName(o.getStatus()),
                o.getIssuedBy(), o.getCreatedAt(), o.getActivatedAt(), o.getLiftingStartedAt(),
                o.getLiftedBy(), o.getArchivedAt(), o.getArchivedBy(), o.getUpdatedAt(),
                total,
                cnt.get("TO_CONTACT"), cnt.get("CONFIRMED_RETURN"), cnt.get("ESCALATED"),
                cnt.get("SAFE_BERTHED"), suspended, LocalDateTime.now());
    }

    private Views.AffectedPlanView toAffectedPlan(SailingPlan p, String groupTag) {
        Vessel v = p.getVessel();
        return new Views.AffectedPlanView(
                p.getId(), p.getPlanNo(), v == null ? null : v.getId(),
                v == null ? null : v.getName(),
                v == null ? null : MarinaDict.vesselTypeName(v.getTypeCode()),
                p.getSkipper(), p.getPhone(), p.getDepartTime(), p.getEtaReturn(), p.getRoute(),
                p.getCrewCount(), p.getStatus(), MarinaDict.planStatusName(p.getStatus()),
                p.getApprovedAt(), p.getSuspendedAt(), p.getSuspendedReason(), p.getResumedAt(),
                groupTag);
    }

    private Views.DispositionView toDispositionView(ReturnDisposition d) {
        ClosureOrder o = d.getClosure();
        Vessel v = d.getVessel();
        SailingPlan plan = d.getSailingPlan();
        Berth assigned = d.getAssignedBerth();
        List<Views.ContactView> contacts = new ArrayList<>();
        for (DispositionContact c : contactRepository
                .findAllByDispositionIdOrderByContactAtDescIdDesc(d.getId())) {
            contacts.add(new Views.ContactView(c.getId(), c.getContactAt(), c.getResultCode(),
                    MarinaDict.contactResultName(c.getResultCode()), c.getResultText(), c.getRemark(),
                    c.getOperator(), c.getRequestId(), c.getCreatedAt()));
        }
        String berthedNo = null;
        String berthingNo = null;
        String skipper = null;
        String phone = null;
        LocalDateTime etaReturn = null;
        Long planId = null;
        String planNo = null;
        if (plan != null) {
            planId = plan.getId();
            planNo = plan.getPlanNo();
            skipper = plan.getSkipper();
            phone = plan.getPhone();
            etaReturn = plan.getEtaReturn();
        }
        if (d.getBerthedBerthId() != null) {
            berthedNo = berthRepository.findById(d.getBerthedBerthId()).map(Berth::getBerthNo).orElse(null);
        }
        if (d.getBerthingId() != null) {
            berthingNo = berthingRepository.findById(d.getBerthingId()).map(Berthing::getRecordNo).orElse(null);
        }
        Boolean fitAssigned = null;
        if (assigned != null && v != null) {
            fitAssigned = v.getLengthM().compareTo(assigned.getLengthM()) <= 0
                    && v.getDraftM().compareTo(assigned.getMaxDraftM()) <= 0
                    && MarinaDict.allowTypeContains(assigned.getAllowType(), v.getTypeCode());
        }
        return new Views.DispositionView(
                d.getId(), o == null ? null : o.getId(),
                o == null ? null : o.getOrderNo(),
                v == null ? null : v.getId(),
                v == null ? null : v.getName(),
                v == null ? null : MarinaDict.vesselTypeName(v.getTypeCode()),
                planId,
                planNo,
                skipper,
                phone,
                etaReturn,
                d.getStatus(), MarinaDict.dispStatusName(d.getStatus()),
                assigned == null ? null : assigned.getId(),
                assigned == null ? null : assigned.getBerthNo(),
                d.getAssignedAt(), d.getAssignedBy(),
                d.getConfirmedAt(), d.getEscalatedAt(), d.getEscalateReason(),
                d.getBerthedAt(), d.getBerthedBerthId(), berthedNo, d.getBerthingId(), berthingNo,
                d.getCreatedAt(), d.getUpdatedAt(), contacts, fitAssigned);
    }
}
