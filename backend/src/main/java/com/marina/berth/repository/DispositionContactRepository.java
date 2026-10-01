package com.marina.berth.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.DispositionContact;

/** 返港处置联系记录。 */
public interface DispositionContactRepository extends JpaRepository<DispositionContact, Long> {

    List<DispositionContact> findAllByDispositionIdOrderByContactAtDescIdDesc(Long dispositionId);

    /** 幂等键查重：重复点击 / 超时重试同一 requestId 时返回已存在的那条 */
    Optional<DispositionContact> findByRequestId(String requestId);
}
