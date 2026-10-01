package com.marina.berth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.marina.berth.domain.SailingPlan;

import jakarta.persistence.LockModeType;

/** 出航申报。 */
public interface SailingPlanRepository extends JpaRepository<SailingPlan, Long> {

    List<SailingPlan> findAllByOrderByDepartTimeDesc();

    List<SailingPlan> findAllByStatusOrderByDepartTimeDesc(String status);

    List<SailingPlan> findAllByVesselIdOrderByDepartTimeDesc(Long vesselId);

    boolean existsByPlanNo(String planNo);

    /** 批准放行时对申报行加锁：两个值班员并发批准同一申报时串行化，避免重复结算离泊 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SailingPlan p where p.id = :id")
    Optional<SailingPlan> findByIdForUpdate(@Param("id") Long id);

    /** 封航生效时要暂停的申报：已批准且出航时间未到（已批准但尚未出航），按原审批顺序 */
    @Query("select p from SailingPlan p where p.status = 'APPROVED' and p.departTime > :now "
            + "order by p.approvedAt asc, p.id asc")
    List<SailingPlan> findApprovedNotDeparted(@Param("now") LocalDateTime now);

    /**
     * 暂停放行批量条件更新：只翻当前仍是 APPROVED 的行，返回实际更新条数。
     * 与审批事务通过封航令行锁串行，条件更新是第二道保险。
     */
    @Modifying
    @Query("update SailingPlan p set p.status = 'SUSPENDED', p.suspendedAt = :now, "
            + "p.suspendedReason = :reason, p.resumedAt = null, p.updatedAt = :now "
            + "where p.status = 'APPROVED' and p.departTime > :now")
    int suspendApprovedNotDeparted(@Param("now") LocalDateTime now,
                                   @Param("reason") String reason);

    /** 某条封航令暂停过、当前仍挂起的申报（解除时恢复，按原审批顺序） */
    @Query("select p from SailingPlan p where p.status = 'SUSPENDED' and p.id in "
            + "(select l.plan.id from PlanSuspendLog l where l.closure.id = :closureId and l.action = 'SUSPEND') "
            + "order by p.approvedAt asc, p.id asc")
    List<SailingPlan> findSuspendedByClosure(@Param("closureId") Long closureId);

    /** 解除封航：批量恢复为 APPROVED（可继续审批，不会自动批准），保留 suspendedReason 留痕 */
    @Modifying
    @Query("update SailingPlan p set p.status = 'APPROVED', p.resumedAt = :now, p.updatedAt = :now "
            + "where p.status = 'SUSPENDED' and p.id in :ids")
    int resumeByIds(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
