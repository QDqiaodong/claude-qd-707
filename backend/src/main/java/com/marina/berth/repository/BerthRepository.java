package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.Berth;

import jakarta.persistence.LockModeType;

/** 泊位。列表查询只走普通排序查询，「当前船只」由 @JoinFormula 派生列直接带出来。 */
public interface BerthRepository extends JpaRepository<Berth, Long> {

    List<Berth> findAllByOrderByBerthNoAsc();

    List<Berth> findAllByZoneCodeOrderByBerthNoAsc(String zoneCode);

    List<Berth> findAllByStatusOrderByBerthNoAsc(String status);

    boolean existsByBerthNo(String berthNo);

    /**
     * 行锁加载泊位：返港安全靠泊时先锁泊位再做占用校验，
     * 两艘船同时选同一个泊位的并发事务在这里排队，后到者会看到前者已经把泊位置为在泊。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Berth b where b.id = :id")
    Optional<Berth> findByIdForUpdate(@Param("id") Long id);
}
