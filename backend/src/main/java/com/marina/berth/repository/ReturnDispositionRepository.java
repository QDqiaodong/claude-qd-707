package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.ReturnDisposition;

import jakarta.persistence.LockModeType;

/** 返港处置项。 */
public interface ReturnDispositionRepository extends JpaRepository<ReturnDisposition, Long> {

    List<ReturnDisposition> findAllByClosureIdOrderByIdAsc(Long closureId);

    List<ReturnDisposition> findAllByVesselIdOrderByIdDesc(Long vesselId);

    Optional<ReturnDisposition> findByClosureIdAndVesselId(Long closureId, Long vesselId);

    /** 行锁读取：并发确认/靠泊时只有一个事务能推进状态 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ReturnDisposition d where d.id = :id")
    Optional<ReturnDisposition> findByIdForUpdate(@Param("id") Long id);

    long countByClosureIdAndStatus(Long closureId, String status);

    /** 归档闸门：失联升级 或 已确认返港但尚未安全靠泊 的处置项数（必须为 0 才能归档） */
    @Query("select count(d) from ReturnDisposition d where d.closure.id = :closureId "
            + "and d.status in ('ESCALATED','CONFIRMED_RETURN')")
    long countRiskOpen(@Param("closureId") Long closureId);

    long countByClosureId(@Param("closureId") Long closureId);
}
