package com.marina.berth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.NavigationClosureOrder;

import jakarta.persistence.LockModeType;

/** 封航令。 */
public interface ClosureOrderRepository extends JpaRepository<NavigationClosureOrder, Long> {

    List<NavigationClosureOrder> findAllByOrderByCreatedAtDescIdDesc();

    List<NavigationClosureOrder> findAllByStatusNotOrderByCreatedAtDescIdDesc(String status);

    Optional<NavigationClosureOrder> findFirstByStatusNotOrderByIdDesc(String status);

    boolean existsByOrderNo(String orderNo);

    /** 到点仍待生效的封航令（定时任务激活用） */
    List<NavigationClosureOrder> findByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAsc(
            String status, LocalDateTime now);

    /** 行锁加载：封航令所有状态流转都先拿这把锁，串行化双人并发操作 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from NavigationClosureOrder o where o.id = :id")
    Optional<NavigationClosureOrder> findByIdForUpdate(@Param("id") Long id);
}
