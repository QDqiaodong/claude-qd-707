package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.DispositionContact;

/** 返港联系记录。(处置项, 幂等令牌) 唯一约束在库表兜底。 */
public interface DispositionContactRepository extends JpaRepository<DispositionContact, Long> {

    List<DispositionContact> findAllByDispositionIdOrderByContactTimeDescIdDesc(Long dispositionId);

    Optional<DispositionContact> findByDispositionIdAndClientRequestId(Long dispositionId, String clientRequestId);
}
