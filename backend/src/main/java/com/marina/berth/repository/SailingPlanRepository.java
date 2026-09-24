package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.SailingPlan;

/** 出航申报。 */
public interface SailingPlanRepository extends JpaRepository<SailingPlan, Long> {

    List<SailingPlan> findAllByOrderByDepartTimeDesc();

    List<SailingPlan> findAllByStatusOrderByDepartTimeDesc(String status);

    List<SailingPlan> findAllByVesselIdOrderByDepartTimeDesc(Long vesselId);

    boolean existsByPlanNo(String planNo);
}
