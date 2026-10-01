package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.PlanSuspendLog;

/** 申报暂停/恢复审计日志。 */
public interface PlanSuspendLogRepository extends JpaRepository<PlanSuspendLog, Long> {

    List<PlanSuspendLog> findAllByClosureIdOrderByIdAsc(Long closureId);

    List<PlanSuspendLog> findAllByPlanIdOrderByIdAsc(Long planId);
}
