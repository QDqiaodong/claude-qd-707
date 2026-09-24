package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.Vessel;

/** 船只台账。「当前泊位」由 @JoinFormula 派生列带出。 */
public interface VesselRepository extends JpaRepository<Vessel, Long> {

    List<Vessel> findAllByOrderByRegNoAsc();

    List<Vessel> findAllByStatusOrderByRegNoAsc(String status);

    List<Vessel> findAllByTypeCodeOrderByRegNoAsc(String typeCode);

    boolean existsByRegNo(String regNo);

    boolean existsByName(String name);
}
