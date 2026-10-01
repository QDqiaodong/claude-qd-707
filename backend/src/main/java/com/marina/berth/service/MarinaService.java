package com.marina.berth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marina.berth.domain.Berth;
import com.marina.berth.domain.ClosureOrder;
import com.marina.berth.domain.MarinaDict;
import com.marina.berth.domain.SailingPlan;
import com.marina.berth.domain.ServiceRecord;
import com.marina.berth.domain.Vessel;
import com.marina.berth.dto.Views;
import com.marina.berth.repository.BerthRepository;
import com.marina.berth.repository.ClosureOrderRepository;
import com.marina.berth.repository.ReturnDispositionRepository;
import com.marina.berth.repository.SailingPlanRepository;
import com.marina.berth.repository.ServiceRecordRepository;
import com.marina.berth.repository.VesselRepository;

/** 码头总览：顶栏那排计数器的数据来源。 */
@Service
public class MarinaService {

    private final BerthRepository berthRepository;
    private final VesselRepository vesselRepository;
    private final SailingPlanRepository planRepository;
    private final ServiceRecordRepository serviceRepository;
    private final ClosureOrderRepository closureRepository;
    private final ReturnDispositionRepository dispRepository;

    public MarinaService(BerthRepository berthRepository,
                         VesselRepository vesselRepository,
                         SailingPlanRepository planRepository,
                         ServiceRecordRepository serviceRepository,
                         ClosureOrderRepository closureRepository,
                         ReturnDispositionRepository dispRepository) {
        this.berthRepository = berthRepository;
        this.vesselRepository = vesselRepository;
        this.planRepository = planRepository;
        this.serviceRepository = serviceRepository;
        this.closureRepository = closureRepository;
        this.dispRepository = dispRepository;
    }

    @Transactional(readOnly = true)
    public Views.Overview overview() {
        long berthTotal = 0;
        long berthFree = 0;
        long berthInPort = 0;
        long berthReserved = 0;
        long berthDisabled = 0;
        for (Berth berth : berthRepository.findAllByOrderByBerthNoAsc()) {
            berthTotal++;
            switch (berth.getStatus()) {
                case "FREE" -> berthFree++;
                case "IN_PORT" -> berthInPort++;
                case "RESERVED" -> berthReserved++;
                default -> berthDisabled++;
            }
        }
        long vesselTotal = 0;
        long vesselInPort = 0;
        long vesselAtSea = 0;
        long vesselMaintenance = 0;
        long insuranceWarn = 0;
        LocalDate today = LocalDate.now();
        for (Vessel vessel : vesselRepository.findAllByOrderByRegNoAsc()) {
            vesselTotal++;
            switch (vessel.getStatus()) {
                case "IN_PORT" -> vesselInPort++;
                case "OUT_SEA" -> vesselAtSea++;
                case "MAINTENANCE" -> vesselMaintenance++;
                default -> {
                }
            }
            long days = ChronoUnit.DAYS.between(today, vessel.getInsuranceDue());
            if (days <= MarinaDict.INSURANCE_WARN_DAYS) {
                insuranceWarn++;
            }
        }
        long planPending = 0;
        long planApproved = 0;
        long planOverdue = 0;
        long planSuspended = 0;
        for (SailingPlan plan : planRepository.findAllByOrderByDepartTimeDesc()) {
            if ("PENDING".equals(plan.getStatus())) {
                planPending++;
            }
            if ("SUSPENDED".equals(plan.getStatus())) {
                planSuspended++;
            }
            if ("APPROVED".equals(plan.getStatus())) {
                planApproved++;
                if (plan.getEtaReturn().isBefore(LocalDateTime.now())) {
                    planOverdue++;
                }
            }
        }
        long serviceUnsettled = 0;
        BigDecimal unsettledAmount = BigDecimal.ZERO;
        BigDecimal monthAmount = BigDecimal.ZERO;
        String thisMonth = YearMonth.from(today).toString();
        for (ServiceRecord item : serviceRepository.findAllByOrderByOccurredDateDescIdDesc()) {
            if ("UNSETTLED".equals(item.getSettleStatus())) {
                serviceUnsettled++;
                unsettledAmount = unsettledAmount.add(item.getAmount() == null ? BigDecimal.ZERO : item.getAmount());
            }
            if (item.getOccurredDate() != null && thisMonth.equals(YearMonth.from(item.getOccurredDate()).toString())) {
                monthAmount = monthAmount.add(item.getAmount() == null ? BigDecimal.ZERO : item.getAmount());
            }
        }
        // 封航：活动令（待生效/生效中/解除中）数、生效中令数、未清零的返港风险项数
        long closureActive = 0;
        long closureEffective = 0;
        for (ClosureOrder c : closureRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if ("ARCHIVED".equals(c.getStatus())) {
                continue;
            }
            closureActive++;
            if ("EFFECTIVE".equals(c.getStatus())) {
                closureEffective++;
            }
        }
        long dispRiskOpen = 0;
        for (ClosureOrder c : closureRepository.findAllByOrderByCreatedAtDescIdDesc()) {
            if ("ARCHIVED".equals(c.getStatus())) {
                continue;
            }
            dispRiskOpen += dispRepository.countRiskOpen(c.getId());
        }
        return new Views.Overview(berthTotal, berthFree, berthInPort, berthReserved, berthDisabled,
                vesselTotal, vesselInPort, vesselAtSea, vesselMaintenance,
                planPending, planApproved, planOverdue, planSuspended,
                closureActive, closureEffective, dispRiskOpen,
                serviceUnsettled, unsettledAmount.setScale(2, RoundingMode.HALF_UP),
                monthAmount.setScale(2, RoundingMode.HALF_UP), insuranceWarn);
    }
}
