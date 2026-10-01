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
 * 模块六：封航与返港处置台。
 * 封航令四阶段 + 受影响申报 / 在海船只处置项 / 可接纳泊位 在同一处置台详情接口里一次返回。
 */
@RestController
@RequestMapping("/api/closures")
public class ClosureController {

    private final ClosureService closureService;

    public ClosureController(ClosureService closureService) {
        this.closureService = closureService;
    }

    /** 封航令列表（默认只看未归档；includeArchived=true 含历史，历史可追溯） */
    @GetMapping
    public ApiResponse<List<Views.ClosureOrderView>> list(
            @RequestParam(name = "includeArchived", required = false, defaultValue = "false")
            boolean includeArchived) {
        return ApiResponse.success(closureService.list(includeArchived));
    }

    /** 处置台详情：封航令 + 受影响申报 + 处置项（含每次联系记录）+ 可接纳泊位 + 操作日志 */
    @GetMapping("/{id}")
    public ApiResponse<Views.ClosureDetailView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(closureService.detail(id));
    }

    /** 发布封航令（生效时间已到立即生效，否则待生效） */
    @PostMapping
    public ApiResponse<Views.ClosureOrderView> create(@Valid @RequestBody ClosureDtos.ClosureCreateReq req) {
        Views.ClosureOrderView view = closureService.create(req);
        boolean effective = "EFFECTIVE".equals(view.status());
        return ApiResponse.success(effective
                ? "封航令已发布并立即生效，在海船只已生成返港处置项，已批准未出航申报已暂停放行"
                : "封航令已发布，将于 " + view.effectiveAt() + " 自动生效", view);
    }

    /** 手动激活待生效封航令 */
    @PostMapping("/{id}/activate")
    public ApiResponse<Views.ClosureOrderView> activate(@PathVariable("id") Long id,
                                                        @RequestBody(required = false) ClosureDtos.ActivateReq req) {
        return ApiResponse.success("封航令已生效，返港处置项与暂停放行已就位",
                closureService.activate(id, req == null ? null : req.operator()));
    }

    /** 申请解除：进入解除中（不自动批准任何暂停申报） */
    @PostMapping("/{id}/release")
    public ApiResponse<Views.ClosureOrderView> release(@PathVariable("id") Long id,
                                                       @Valid @RequestBody(required = false) ClosureDtos.ReleaseReq req) {
        return ApiResponse.success("已进入解除中，风险处置项清零后方可归档；暂停申报未被自动批准",
                closureService.release(id, req));
    }

    /** 归档：风险项清零后才允许；暂停申报恢复为可继续审批，保留顺序与原因 */
    @PostMapping("/{id}/archive")
    public ApiResponse<Views.ClosureOrderView> archive(@PathVariable("id") Long id,
                                                       @RequestBody(required = false) ClosureDtos.ArchiveReq req) {
        return ApiResponse.success("封航令已归档；暂停申报恢复为可继续审批（未自动批准），原审批顺序与暂停原因已保留",
                closureService.archive(id, req == null ? null : req.operator()));
    }

    /** 处置项联系操作（联系上/确认返港/失联升级/重新取得联系），clientRequestId 保证幂等 */
    @PostMapping("/dispositions/{id}/contact")
    public ApiResponse<Views.DispositionView> contact(@PathVariable("id") Long id,
                                                      @Valid @RequestBody ClosureDtos.ContactReq req) {
        return ApiResponse.success("处置结果已记录", closureService.contact(id, req));
    }

    /** 安全靠泊：处置项/申报/船只/靠泊记录/泊位一笔事务，失败整笔回退 */
    @PostMapping("/dispositions/{id}/safe-berth")
    public ApiResponse<Views.SafeBerthResult> safeBerth(@PathVariable("id") Long id,
                                                        @Valid @RequestBody ClosureDtos.SafeBerthReq req) {
        return ApiResponse.success("安全靠泊完成，处置项、申报、船只、靠泊记录与泊位已一次性保持一致",
                closureService.safeBerth(id, req));
    }
}
