package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.SailingPlan;

/** 出航申报。 */
public interface SailingPlanRepository extends JpaRepository<SailingPlan, Long> {

    List<SailingPlan> findAllByOrderByDepartTimeDesc();

    List<SailingPlan> findAllByStatusOrderByDepartTimeDesc(String status);

    List<SailingPlan> findAllByVesselIdOrderByDepartTimeDesc(Long vesselId);

    /** 被某条封航令暂停放行的申报，按原审批顺序取回（解除恢复时必须用这个顺序） */
    List<SailingPlan> findAllByHoldByOrderIdOrderByHoldSeqAscIdAsc(Long closureOrderId);

    boolean existsByPlanNo(String planNo);
}
