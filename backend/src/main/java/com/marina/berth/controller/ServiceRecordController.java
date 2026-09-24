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
import com.marina.berth.dto.ServiceRecordDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.ServiceRecordService;

import jakarta.validation.Valid;

/** 模块五：码头服务与费用。 */
@RestController
@RequestMapping("/api/service-records")
public class ServiceRecordController {

    private final ServiceRecordService serviceRecordService;

    public ServiceRecordController(ServiceRecordService serviceRecordService) {
        this.serviceRecordService = serviceRecordService;
    }

    @GetMapping
    public ApiResponse<List<Views.ServiceRecordView>> list(@RequestParam(required = false) String serviceType,
                                                           @RequestParam(required = false) String settleStatus,
                                                           @RequestParam(required = false) Long vesselId,
                                                           @RequestParam(required = false) Long berthId) {
        return ApiResponse.success(serviceRecordService.list(serviceType, settleStatus, vesselId, berthId));
    }

    /** 月度费用汇总 + 按类型汇总 */
    @GetMapping("/summary")
    public ApiResponse<Views.ServiceSummary> summary() {
        return ApiResponse.success(serviceRecordService.summary());
    }

    @PostMapping
    public ApiResponse<Views.ServiceRecordView> create(@Valid @RequestBody ServiceRecordDtos.ServiceSaveReq req) {
        return ApiResponse.success("服务记录已登记", serviceRecordService.create(req));
    }

    /** 结算状态变更：已结 / 挂账 / 退回未结 */
    @PostMapping("/{id}/settle")
    public ApiResponse<Views.ServiceRecordView> settle(@PathVariable("id") Long id,
                                                       @Valid @RequestBody ServiceRecordDtos.SettleReq req) {
        return ApiResponse.success("结算状态已更新", serviceRecordService.settle(id, req));
    }
}
