package com.marina.berth.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.marina.berth.domain.ClosurePlanEffect;

/** 封航令-受影响申报台账（只追加）。 */
public interface ClosurePlanEffectRepository extends JpaRepository<ClosurePlanEffect, Long> {

    List<ClosurePlanEffect> findAllByClosureOrderIdOrderByIdAsc(Long closureOrderId);
}
