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
import com.marina.berth.dto.BerthingDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.BerthingService;

import jakarta.validation.Valid;

/** 模块三：靠泊记录（在泊 / 已结算离泊）。 */
@RestController
@RequestMapping("/api/berthings")
public class BerthingController {

    private final BerthingService berthingService;

    public BerthingController(BerthingService berthingService) {
        this.berthingService = berthingService;
    }

    @GetMapping
    public ApiResponse<List<Views.BerthingView>> list(@RequestParam(required = false) Long berthId,
                                                      @RequestParam(required = false) Long vesselId,
                                                      @RequestParam(required = false) String status) {
        return ApiResponse.success(berthingService.list(berthId, vesselId, status));
    }

    @PostMapping
    public ApiResponse<Views.BerthingView> create(@Valid @RequestBody BerthingDtos.BerthingSaveReq req) {
        return ApiResponse.success("靠泊已登记", berthingService.create(req));
    }

    /** 办理离泊结算 */
    @PostMapping("/{id}/depart")
    public ApiResponse<Views.BerthingView> depart(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) BerthingDtos.DepartReq req) {
        return ApiResponse.success("离泊已结算，费用已按天数计收",
                berthingService.depart(id, req == null ? null : req.actualDepart()));
    }
}
