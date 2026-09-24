package com.marina.berth.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.marina.berth.common.ApiResponse;
import com.marina.berth.dto.SailingPlanDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.SailingPlanService;

import jakarta.validation.Valid;

/** 模块四：出航申报（待审批 / 已批准 / 已返航 / 已取消，超期未返带超时小时数）。 */
@RestController
@RequestMapping("/api/sailing-plans")
public class SailingPlanController {

    private final SailingPlanService planService;

    public SailingPlanController(SailingPlanService planService) {
        this.planService = planService;
    }

    @GetMapping
    public ApiResponse<List<Views.SailingPlanView>> list(@RequestParam(required = false) Long vesselId,
                                                         @RequestParam(required = false) String status) {
        return ApiResponse.success(planService.list(vesselId, status));
    }

    /** 前端「新建申报」预填单号 */
    @GetMapping("/next-no")
    public ApiResponse<String> nextNo() {
        return ApiResponse.success(planService.suggestPlanNo());
    }

    @PostMapping
    public ApiResponse<Views.SailingPlanView> create(@Valid @RequestBody SailingPlanDtos.PlanSaveReq req) {
        return ApiResponse.success("出航申报已提交，待审批", planService.create(req));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Views.SailingPlanView> approve(@PathVariable("id") Long id) {
        return ApiResponse.success("申报已批准，船只放行出海", planService.approve(id));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<Views.SailingPlanView> markReturn(@PathVariable("id") Long id) {
        return ApiResponse.success("已登记返航", planService.markReturn(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Views.SailingPlanView> cancel(@PathVariable("id") Long id) {
        return ApiResponse.success("申报已取消", planService.cancel(id));
    }
}
