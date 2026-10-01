package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.Vessel;

import jakarta.persistence.LockModeType;

/** 船只台账。「当前泊位」由 @JoinFormula 派生列带出。 */
public interface VesselRepository extends JpaRepository<Vessel, Long> {

    List<Vessel> findAllByOrderByRegNoAsc();

    List<Vessel> findAllByStatusOrderByRegNoAsc(String status);

    List<Vessel> findAllByTypeCodeOrderByRegNoAsc(String typeCode);

    boolean existsByRegNo(String regNo);

    boolean existsByName(String name);

    /** 封航生效快照时给在海船加行锁，保证返港处置项只生成一次 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vessel v where v.status = 'OUT_SEA' order by v.id asc")
    List<Vessel> findOutSeaForUpdate();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Vessel v where v.id = :id")
    Optional<Vessel> findByIdForUpdate(@Param("id") Long id);
}
