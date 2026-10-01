package com.marina.berth.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marina.berth.common.ApiResponse;
import com.marina.berth.dto.ClosureDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.ClosureService;

import jakarta.validation.Valid;

/**
 * 模块七：返港处置项的操作接口（独立基路径 /api/dispositions，避免与封航令 /api/closures/{id}
 * 的路径变量产生匹配歧义）。并发幂等、状态单调、安全靠泊整笔一致都由 {@link ClosureService} 保证。
 */
@RestController
@RequestMapping("/api/dispositions")
public class ReturnDispositionController {

    private final ClosureService closureService;

    public ReturnDispositionController(ClosureService closureService) {
        this.closureService = closureService;
    }

    @GetMapping("/{dispId}")
    public ApiResponse<Views.DispositionView> detail(@PathVariable("dispId") Long dispId) {
        return ApiResponse.success(closureService.dispositionDetail(dispId));
    }

    /** 该船可接纳返港的候选泊位（适泊校验后的空闲 / 本封航预留位） */
    @GetMapping("/{dispId}/candidate-berths")
    public ApiResponse<List<Views.BerthOptionView>> candidateBerths(@PathVariable("dispId") Long dispId) {
        return ApiResponse.success(closureService.candidateBerths(dispId));
    }

    /** 记一次联系（保留时间/结果/备注，requestId 幂等），按结果单调推进处置状态 */
    @PostMapping("/{dispId}/contacts")
    public ApiResponse<Views.DispositionView> addContact(@PathVariable("dispId") Long dispId,
                                                         @Valid @RequestBody ClosureDtos.ContactReq req) {
        return ApiResponse.success("联系已记录", closureService.addContact(dispId, req));
    }

    /** 失联升级（自动留一条失联上报联系记录） */
    @PostMapping("/{dispId}/escalate")
    public ApiResponse<Views.DispositionView> escalate(@PathVariable("dispId") Long dispId,
                                                       @RequestBody(required = false) ClosureDtos.EscalateReq req) {
        ClosureDtos.EscalateReq body = req == null
                ? new ClosureDtos.EscalateReq(null, null, null) : req;
        return ApiResponse.success("已标记失联升级，请按应急预案处置", closureService.escalate(dispId, body));
    }

    /** 预留返港泊位（RESERVED 先到先得，行锁防重复占用） */
    @PostMapping("/{dispId}/assign-berth")
    public ApiResponse<Views.DispositionView> assignBerth(@PathVariable("dispId") Long dispId,
                                                          @Valid @RequestBody ClosureDtos.AssignBerthReq req) {
        return ApiResponse.success("返港泊位已预留", closureService.assignBerth(dispId, req));
    }

    /** 释放返港预留泊位 */
    @PostMapping("/{dispId}/release-berth")
    public ApiResponse<Views.DispositionView> releaseBerth(@PathVariable("dispId") Long dispId,
                                                           @RequestBody(required = false) ClosureDtos.OperatorReq req) {
        String operator = req == null ? null : req.operator();
        return ApiResponse.success("预留泊位已释放", closureService.releaseBerth(dispId, operator));
    }

    /**
     * 登记安全靠泊：处置项 / 出航申报 / 船只 / 靠泊记录 / 泊位 一笔事务，
     * 任一步失败整笔回退（返回文案明确说明哪些数据没有改变）；重复提交幂等。
     */
    @PostMapping("/{dispId}/safe-berth")
    public ApiResponse<Views.DispositionView> safeBerth(@PathVariable("dispId") Long dispId,
                                                        @RequestBody(required = false) ClosureDtos.SafeBerthReq req) {
        ClosureDtos.SafeBerthReq body = req == null
                ? new ClosureDtos.SafeBerthReq(null, null, null, null, null) : req;
        return ApiResponse.success("安全靠泊已登记，申报、船只、靠泊记录与泊位状态已同步一致",
                closureService.safeBerth(dispId, body));
    }
}
