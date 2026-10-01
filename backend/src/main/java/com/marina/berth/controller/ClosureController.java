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
import com.marina.berth.dto.ClosureDtos;
import com.marina.berth.dto.Views;
import com.marina.berth.service.ClosureService;

import jakarta.validation.Valid;

/**
 * 模块六/七：封航与返港处置台。
 *
 * <p>封航令四阶段（待生效/生效中/解除中/已归档）与返港处置四状态（待联系/已确认返港/
 * 失联升级/已安全靠泊）的全部写操作都在这里；安全靠泊的多表一致性、并发幂等由
 * {@link ClosureService} 的行锁 + 单事务 + 幂等键保证。
 */
@RestController
@RequestMapping("/api/closures")
public class ClosureController {

    private final ClosureService closureService;

    public ClosureController(ClosureService closureService) {
        this.closureService = closureService;
    }

    // ---------------- 封航令 ----------------

    @GetMapping
    public ApiResponse<List<Views.ClosureView>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(closureService.list(status));
    }

    /** 当前活动令（至多一条，没有返回 data:null） */
    @GetMapping("/active")
    public ApiResponse<Views.ClosureView> active() {
        return ApiResponse.success(closureService.active());
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.ClosureView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(closureService.detail(id));
    }

    /** 受影响的出航申报：暂停放行（含暂停原因）+ 生效期间的待审批 */
    @GetMapping("/{id}/affected-plans")
    public ApiResponse<List<Views.AffectedPlanView>> affectedPlans(@PathVariable("id") Long id) {
        return ApiResponse.success(closureService.affectedPlans(id));
    }

    /** 封航令下的返港处置项（含每次联系留痕） */
    @GetMapping("/{id}/dispositions")
    public ApiResponse<List<Views.DispositionView>> dispositions(@PathVariable("id") Long id,
                                                                 @RequestParam(required = false) String status) {
        return ApiResponse.success(closureService.dispositions(id, status));
    }

    /** 发布封航令（待生效；生效时间到点自动生效，已到则立即生效） */
    @PostMapping
    public ApiResponse<Views.ClosureView> create(@Valid @RequestBody ClosureDtos.ClosureCreateReq req) {
        return ApiResponse.success("封航令已发布", closureService.create(req));
    }

    /** 手动提前生效 */
    @PostMapping("/{id}/activate")
    public ApiResponse<Views.ClosureView> activate(@PathVariable("id") Long id,
                                                   @RequestBody(required = false) ClosureDtos.ActivateReq req) {
        String operator = req == null ? null : req.operator();
        return ApiResponse.success("封航令已生效，已暂停未出航申报并生成返港处置名单",
                closureService.activate(id, operator));
    }

    /** 发起解除：生效中 → 解除中（暂停申报恢复为可继续审批，不自动批准） */
    @PostMapping("/{id}/lift")
    public ApiResponse<Views.ClosureView> lift(@PathVariable("id") Long id,
                                               @RequestBody(required = false) ClosureDtos.OperatorReq req) {
        String operator = req == null ? null : req.operator();
        return ApiResponse.success("已进入解除中：暂停的申报恢复为可继续审批（未自动批准），请继续清零返港风险项",
                closureService.beginLift(id, operator));
    }

    /** 归档：风险项未清零时只能停在解除中，清零后才允许归档 */
    @PostMapping("/{id}/archive")
    public ApiResponse<Views.ClosureView> archive(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) ClosureDtos.OperatorReq req) {
        String operator = req == null ? null : req.operator();
        return ApiResponse.success("封航令已归档，全流程留痕可追溯", closureService.archive(id, operator));
    }
}
