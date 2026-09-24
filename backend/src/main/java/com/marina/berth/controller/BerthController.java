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
import com.marina.berth.dto.BerthDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.BerthService;
import com.marina.berth.service.BerthingService;

import jakarta.validation.Valid;

/** 模块一：泊位。列表接口直接带出 @JoinFormula 派生出来的「当前在泊船只」。 */
@RestController
@RequestMapping("/api/berths")
public class BerthController {

    private final BerthService berthService;
    private final BerthingService berthingService;

    public BerthController(BerthService berthService, BerthingService berthingService) {
        this.berthService = berthService;
        this.berthingService = berthingService;
    }

    @GetMapping
    public ApiResponse<List<Views.BerthView>> list(@RequestParam(required = false) String zoneCode,
                                                   @RequestParam(required = false) String status) {
        if (zoneCode != null && !zoneCode.isBlank()) {
            return ApiResponse.success(berthService.listByZone(zoneCode.trim().toUpperCase()));
        }
        if (status != null && !status.isBlank()) {
            return ApiResponse.success(berthService.listByStatus(status.trim().toUpperCase()));
        }
        return ApiResponse.success(berthService.list());
    }

    @PostMapping
    public ApiResponse<Views.BerthView> create(@Valid @RequestBody BerthDtos.BerthSaveReq req) {
        return ApiResponse.success("泊位已创建", berthService.create(req));
    }

    /** 安排靠泊 */
    @PostMapping("/{id}/occupy")
    public ApiResponse<Views.BerthView> occupy(@PathVariable("id") Long id,
                                               @RequestBody BerthDtos.OccupyReq req) {
        return ApiResponse.success("靠泊已安排", berthService.occupy(id, req));
    }

    /** 办理离泊结算 */
    @PostMapping("/{id}/depart")
    public ApiResponse<Views.BerthView> depart(@PathVariable("id") Long id,
                                               @RequestBody(required = false) BerthDtos.DepartReq req) {
        return ApiResponse.success("离泊已结算", berthService.depart(id, req));
    }

    /** 泊位状态：空闲 / 预留 / 停用维修 */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.BerthView> changeStatus(@PathVariable("id") Long id,
                                                     @Valid @RequestBody BerthDtos.BerthStatusReq req) {
        return ApiResponse.success("泊位状态已更新", berthService.changeStatus(id, req));
    }

    /** 该泊位的历史靠泊记录 */
    @GetMapping("/{id}/berthings")
    public ApiResponse<List<Views.BerthingView>> berthings(@PathVariable("id") Long id) {
        return ApiResponse.success(berthingService.listByBerth(id));
    }
}
