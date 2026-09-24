package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.Berthing;

/** 靠泊记录。在泊/离泊的唯一事实来源。 */
public interface BerthingRepository extends JpaRepository<Berthing, Long> {

    List<Berthing> findAllByOrderByBerthTimeDesc();

    List<Berthing> findAllByStatusOrderByBerthTimeDesc(String status);

    List<Berthing> findAllByVesselIdOrderByBerthTimeDesc(Long vesselId);

    List<Berthing> findAllByBerthIdOrderByBerthTimeDesc(Long berthId);

    /** 某泊位当前的在泊记录（正常最多一条） */
    Optional<Berthing> findFirstByBerthIdAndStatusOrderByBerthTimeDesc(Long berthId, String status);

    /** 某船当前的在泊记录 */
    Optional<Berthing> findFirstByVesselIdAndStatusOrderByBerthTimeDesc(Long vesselId, String status);

    boolean existsByRecordNo(String recordNo);
}
