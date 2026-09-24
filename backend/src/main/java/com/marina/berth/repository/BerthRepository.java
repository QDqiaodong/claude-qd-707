package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.Berth;

/** 泊位。列表接口只走普通排序查询，「当前船只」由 @JoinFormula 派生列直接带出来。 */
public interface BerthRepository extends JpaRepository<Berth, Long> {

    List<Berth> findAllByOrderByBerthNoAsc();

    List<Berth> findAllByZoneCodeOrderByBerthNoAsc(String zoneCode);

    List<Berth> findAllByStatusOrderByBerthNoAsc(String status);

    boolean existsByBerthNo(String berthNo);
}
