package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.ServiceRecord;

/** 码头服务与费用记录。 */
public interface ServiceRecordRepository extends JpaRepository<ServiceRecord, Long> {

    List<ServiceRecord> findAllByOrderByOccurredDateDescIdDesc();

    List<ServiceRecord> findAllByServiceTypeOrderByOccurredDateDesc(String serviceType);

    List<ServiceRecord> findAllBySettleStatusOrderByOccurredDateDesc(String settleStatus);

    List<ServiceRecord> findAllByVesselIdOrderByOccurredDateDesc(Long vesselId);

    List<ServiceRecord> findAllByBerthIdOrderByOccurredDateDesc(Long berthId);
}
