package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.ReturnDisposition;

import jakarta.persistence.LockModeType;

/** 返港处置项。(封航令, 船只) 唯一约束在库表兜底。 */
public interface ReturnDispositionRepository extends JpaRepository<ReturnDisposition, Long> {

    List<ReturnDisposition> findAllByClosureOrderIdOrderByIdAsc(Long closureOrderId);

    Optional<ReturnDisposition> findByClosureOrderIdAndVesselId(Long closureOrderId, Long vesselId);

    /** 某封航令下尚未安全靠泊的处置项（风险项） */
    List<ReturnDisposition> findByClosureOrderIdAndStatusNotOrderByIdAsc(Long closureOrderId, String status);

    long countByClosureOrderIdAndStatus(Long closureOrderId, String status);

    /**
     * 行锁加载处置项：所有状态推进（确认 / 升级 / 靠泊 / 幂等重试）先拿这把锁，
     * 两名值班员同时点同一条时，后到的事务看到的是前一个事务提交后的最新状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ReturnDisposition d where d.id = :id")
    Optional<ReturnDisposition> findByIdForUpdate(@Param("id") Long id);
}
