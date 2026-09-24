package com.marina.berth.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.common.BizException;
import com.marina.berth.domain.Berth;
import com.marina.berth.domain.Berthing;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.BerthDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.BerthingRepository;

/**
 * 模块一：泊位。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>泊位号全局唯一；区域只能 A / B / C</li>
 *   <li>新建泊位不能是「在泊」，在泊只能由「安排靠泊」产生</li>
 *   <li>停用维修的泊位不允许安排靠泊；留有船的泊位不允许直接停用</li>
 *   <li>预留泊位要先取消预留才能安排靠泊</li>
 * </ol>
 *
 * <p>本仓的数据访问主路线在这里体现：{@link Berth#getCurrentVessel()} 是实体上一条
 * {@code @JoinFormula} 派生关联 —— 列表接口不做任何 join、不加任何冗余外键，
 * 直接把「当前在泊船只」的船名、船型、船主带出来给前端泊位平面图用。
 */
@Service
public class BerthService {

    private final BerthRepository berthRepository;
    private final BerthingRepository berthingRepository;
    private final BerthingService berthingService;

    @PersistenceContext
    private EntityManager entityManager;

    public BerthService(BerthRepository berthRepository,
                        BerthingRepository berthingRepository,
                        BerthingService berthingService) {
        this.berthRepository = berthRepository;
        this.berthingRepository = berthingRepository;
        this.berthingService = berthingService;
    }

    /** 泊位列表：currentVessel* 一并由 @JoinFormula 派生列带出 */
    @Transactional(readOnly = true)
    public List<Views.BerthView> list() {
        return toViews(berthRepository.findAllByOrderByBerthNoAsc());
    }

    @Transactional(readOnly = true)
    public List<Views.BerthView> listByZone(String zoneCode) {
        return toViews(berthRepository.findAllByZoneCodeOrderByBerthNoAsc(zoneCode));
    }

    @Transactional(readOnly = true)
    public List<Views.BerthView> listByStatus(String status) {
        return toViews(berthRepository.findAllByStatusOrderByBerthNoAsc(status));
    }

    @Transactional
    public Views.BerthView create(BerthDtos.BerthSaveReq req) {
        String berthNo = trim(req.berthNo());
        if (berthRepository.existsByBerthNo(berthNo)) {
            throw new BizException("泊位号 " + berthNo + " 已存在，不能重复建");
        }
        String zone = trim(req.zoneCode()).toUpperCase();
        if (!MarinaDict.isValidZone(zone)) {
            throw new BizException("所属区域只能是 A / B / C 区，收到的是：" + req.zoneCode());
        }
        String status = req.status() == null || req.status().isBlank()
                ? "FREE"
                : req.status().trim().toUpperCase();
        if (!MarinaDict.isValidBerthNewStatus(status)) {
            throw new BizException("新建泊位只能是 FREE（空闲）/ RESERVED（预留）/ DISABLED（停用维修），"
                    + "在泊请走「安排靠泊」");
        }
        String utility = req.utility() == null || req.utility().isBlank()
                ? "POWER_WATER"
                : req.utility().trim().toUpperCase();
        if (!MarinaDict.isValidUtility(utility)) {
            throw new BizException("水电接口只能是 POWER_WATER / POWER / WATER / NONE，收到的是：" + req.utility());
        }
        String allowType = req.allowType() == null || req.allowType().isBlank()
                ? defaultAllowType(zone)
                : req.allowType().trim().toUpperCase();
        for (String part : allowType.split(";")) {
            if (!part.trim().isEmpty() && !MarinaDict.isValidVesselType(part.trim())) {
                throw new BizException("允许船型只能是 SAIL / POWER / FISHING / CATAMARAN / SPEED，收到的是：" + part);
            }
        }
        BigDecimal lengthM = req.lengthM();
        if (lengthM == null || lengthM.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("泊位长度必须大于 0");
        }
        BigDecimal maxDraft = req.maxDraftM();
        if (maxDraft == null || maxDraft.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("最大吃水必须大于 0");
        }
        BigDecimal dailyRate = req.dailyRate();
        if (dailyRate == null || dailyRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("日租金必须大于 0");
        }

        Berth berth = new Berth();
        berth.setBerthNo(berthNo);
        berth.setZoneCode(zone);
        berth.setPierNo(req.pierNo() == null || req.pierNo().isBlank()
                ? zone + (berthRepository.count() % 2 + 1)
                : req.pierNo().trim());
        berth.setLengthM(lengthM);
        berth.setMaxDraftM(maxDraft);
        berth.setAllowType(allowType);
        berth.setDailyRate(dailyRate);
        berth.setUtility(utility);
        berth.setStatus(status);
        berth.setUpdatedAt(LocalDateTime.now());
        Berth saved = berthRepository.save(berth);
        return toView(saved, new HashMap<>());
    }

    /** 安排靠泊：交给靠泊记录模块统一落库，泊位状态随之转「在泊」 */
    @Transactional
    public Views.BerthView occupy(Long berthId, BerthDtos.OccupyReq req) {
        if (req == null || req.vesselId() == null) {
            throw new BizException("请先选择要安排的船只");
        }
        berthingService.arrive(berthId, req.vesselId(), LocalDateTime.now(), req.etaDepart(), null);
        return toView(reload(berthId), new HashMap<>());
    }

    /** 办理离泊：把该泊位当前那条在泊记录结算掉 */
    @Transactional
    public Views.BerthView depart(Long berthId, BerthDtos.DepartReq req) {
        Berthing open = berthingRepository.findFirstByBerthIdAndStatusOrderByBerthTimeDesc(berthId, "IN_PORT")
                .orElseThrow(() -> new BizException("该泊位当前没有在泊船只，无需办理离泊"));
        berthingService.depart(open.getId(), req == null ? null : req.actualDepart());
        return toView(reload(berthId), new HashMap<>());
    }

    /** 泊位状态变更：空闲 / 预留 / 停用维修 */
    @Transactional
    public Views.BerthView changeStatus(Long berthId, BerthDtos.BerthStatusReq req) {
        Berth berth = berthRepository.findById(berthId)
                .orElseThrow(() -> new BizException("泊位不存在：id=" + berthId));
        String status = req.status() == null ? "" : req.status().trim().toUpperCase();
        if (!MarinaDict.isValidBerthNewStatus(status)) {
            throw new BizException("泊位状态只能是 FREE / RESERVED / DISABLED，收到的是：" + req.status());
        }
        if ("IN_PORT".equals(berth.getStatus())) {
            throw new BizException("泊位 " + berth.getBerthNo() + " 上有船在泊，请先办理离泊");
        }
        berth.setStatus(status);
        berth.setUpdatedAt(LocalDateTime.now());
        return toView(reload(berthId), new HashMap<>());
    }

    /**
     * 写完重读泊位。
     *
     * <p>{@link Berth#getCurrentVessel()} 这条 {@code @JoinFormula} 派生关联的值是【实体加载那一刻】
     * 由子查询算出来的，之后就缓存在持久化上下文里。同一个事务里刚安排靠泊 / 办完离泊后，
     * findById 拿到的还是那个旧实例，派生列不会自己重算 —— 必须 flush 之后清空持久化上下文，
     * 让下一次 findById 真去数据库重查（连子查询一起），否则接口返回给前端的「当前船只」是上一秒的旧值。
     */
    private Berth reload(Long berthId) {
        entityManager.flush();
        entityManager.clear();
        return berthRepository.findById(berthId)
                .orElseThrow(() -> new BizException("泊位不存在：id=" + berthId));
    }

    private List<Views.BerthView> toViews(List<Berth> berths) {
        // 一次查出所有在泊记录，避免在循环里逐条查（泊位平面图一次要画 20+ 个泊位）
        Map<Long, Berthing> openByBerth = new HashMap<>();
        for (Berthing item : berthingRepository.findAllByStatusOrderByBerthTimeDesc("IN_PORT")) {
            if (item.getBerth() != null) {
                openByBerth.put(item.getBerth().getId(), item);
            }
        }
        List<Views.BerthView> list = new ArrayList<>();
        for (Berth berth : berths) {
            list.add(toView(berth, openByBerth));
        }
        return list;
    }

    /**
     * 泊位视图：{@code currentVessel*} 全部来自实体上的 {@code @JoinFormula} 派生关联
     * {@link Berth#getCurrentVessel()}，只读，不参与写入。
     */
    public Views.BerthView toView(Berth berth, Map<Long, Berthing> openByBerth) {
        Vessel vessel = berth.getCurrentVessel();
        Berthing open = openByBerth == null ? null : openByBerth.get(berth.getId());
        if (open == null && berth.getId() != null) {
            open = berthingRepository.findFirstByBerthIdAndStatusOrderByBerthTimeDesc(berth.getId(), "IN_PORT")
                    .orElse(null);
        }
        return new Views.BerthView(
                berth.getId(),
                berth.getBerthNo(),
                berth.getZoneCode(),
                berth.getPierNo(),
                berth.getLengthM(),
                berth.getMaxDraftM(),
                berth.getAllowType(),
                MarinaDict.allowTypeNames(berth.getAllowType()),
                berth.getDailyRate(),
                berth.getUtility(),
                MarinaDict.utilityName(berth.getUtility()),
                berth.getStatus(),
                MarinaDict.berthStatusName(berth.getStatus()),
                vessel == null ? null : vessel.getId(),
                vessel == null ? null : vessel.getName(),
                vessel == null ? null : vessel.getTypeCode(),
                vessel == null ? null : MarinaDict.vesselTypeName(vessel.getTypeCode()),
                vessel == null ? null : vessel.getOwnerName(),
                vessel == null ? null : vessel.getLengthM(),
                vessel == null ? null : vessel.getDraftM(),
                open == null ? null : open.getId(),
                open == null ? null : open.getRecordNo(),
                open == null ? null : open.getBerthTime(),
                open == null ? null : open.getEtaDepart(),
                berth.getUpdatedAt());
    }

    /** 区域默认允许船型：A 区小船位、B 区中泊位、C 区大泊位 */
    private String defaultAllowType(String zone) {
        return switch (zone) {
            case "A" -> "SAIL;FISHING;SPEED";
            case "B" -> "POWER;CATAMARAN;FISHING";
            default -> "POWER;CATAMARAN";
        };
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
