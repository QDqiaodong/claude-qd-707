package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.ClosureOrder;

import jakarta.persistence.LockModeType;

/** 封航令。 */
public interface ClosureOrderRepository extends JpaRepository<ClosureOrder, Long> {

    boolean existsByOrderNo(String orderNo);

    List<ClosureOrder> findAllByOrderByCreatedAtDescIdDesc();

    List<ClosureOrder> findAllByStatusOrderByEffectiveAtAsc(String status);

    Optional<ClosureOrder> findFirstByStatusOrderByEffectiveAtAsc(String status);

    /** 是否存在未归档（待生效/生效中/解除中）的封航令：同一时段只允许一张活动令 */
    @Query("select count(c) > 0 from ClosureOrder c where c.status in ('SCHEDULED','EFFECTIVE','LIFTING')")
    boolean existsActive();

    /** 是否存在生效中的封航令（批准放行的闸门条件） */
    @Query("select count(c) > 0 from ClosureOrder c where c.status = 'EFFECTIVE'")
    boolean existsEffective();

    /** 到点该生效的待生效令（定时任务扫，行锁由 service 侧 findByIdForUpdate 补） */
    List<ClosureOrder> findByStatusAndEffectiveAtLessThanEqualOrderByEffectiveAtAsc(
            String status, java.time.LocalDateTime now);

    /**
     * 锁定全部活动封航令（待生效/生效中/解除中）。批准放行与封航生效都先取这把锁，
     * 保证「生效瞬间批准」不会穿过闸门。锁顺序固定为 封航令 → 申报/船只/泊位，避免死锁。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClosureOrder c where c.status in ('SCHEDULED','EFFECTIVE','LIFTING') order by c.id asc")
    List<ClosureOrder> findActiveForUpdate();

    /** 行锁单条令：生效/解除/归档状态机推进用 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ClosureOrder c where c.id = :id")
    Optional<ClosureOrder> findByIdForUpdate(@Param("id") Long id);
}
