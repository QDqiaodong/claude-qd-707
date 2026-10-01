package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.Berth;

import jakarta.persistence.LockModeType;

/** 泊位。列表接口只走普通排序查询，「当前船只」由 @JoinFormula 派生列直接带出来。 */
public interface BerthRepository extends JpaRepository<Berth, Long> {

    List<Berth> findAllByOrderByBerthNoAsc();

    List<Berth> findAllByZoneCodeOrderByBerthNoAsc(String zoneCode);

    List<Berth> findAllByStatusOrderByBerthNoAsc(String status);

    boolean existsByBerthNo(String berthNo);

    /** 行锁读取：预留 / 安全靠泊占用泊位时串行化，杜绝重复占用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Berth b where b.id = :id")
    Optional<Berth> findByIdForUpdate(@Param("id") Long id);
}
