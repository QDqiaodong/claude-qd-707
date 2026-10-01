package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.ClosureLog;

/** 封航操作日志（只追加）。 */
public interface ClosureLogRepository extends JpaRepository<ClosureLog, Long> {

    List<ClosureLog> findAllByClosureOrderIdOrderByIdAsc(Long closureOrderId);
}
