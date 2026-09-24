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
import com.marina.berth.dto.Views;
import com.marina.berth.dto.VesselDtos;
import com.marina.berth.service.BerthingService;
import com.marina.berth.service.VesselService;

import jakarta.validation.Valid;

/** 模块二：船只台账。「当前泊位」由 @JoinFormula 派生关联带出。 */
@RestController
@RequestMapping("/api/vessels")
public class VesselController {

    private final VesselService vesselService;
    private final BerthingService berthingService;

    public VesselController(VesselService vesselService, BerthingService berthingService) {
        this.vesselService = vesselService;
        this.berthingService = berthingService;
    }

    @GetMapping
    public ApiResponse<List<Views.VesselView>> list(@RequestParam(required = false) String status,
                                                    @RequestParam(required = false) String typeCode) {
        if (status != null && !status.isBlank()) {
            return ApiResponse.success(vesselService.listByStatus(status.trim().toUpperCase()));
        }
        if (typeCode != null && !typeCode.isBlank()) {
            return ApiResponse.success(vesselService.listByType(typeCode.trim().toUpperCase()));
        }
        return ApiResponse.success(vesselService.list());
    }

    @PostMapping
    public ApiResponse<Views.VesselView> create(@Valid @RequestBody VesselDtos.VesselSaveReq req) {
        return ApiResponse.success("船只已登记", vesselService.create(req));
    }

    /** 状态变更：在港 / 出海 / 维修 / 退租 */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.VesselView> changeStatus(@PathVariable("id") Long id,
                                                      @Valid @RequestBody VesselDtos.VesselStatusReq req) {
        return ApiResponse.success("船只状态已更新", vesselService.changeStatus(id, req));
    }

    /** 续保 */
    @PostMapping("/{id}/insurance")
    public ApiResponse<Views.VesselView> renewInsurance(@PathVariable("id") Long id,
                                                        @Valid @RequestBody VesselDtos.InsuranceReq req) {
        return ApiResponse.success("保险已续保", vesselService.renewInsurance(id, req));
    }

    /** 该船的历史靠泊记录（船只台账抽屉里用） */
    @GetMapping("/{id}/berthings")
    public ApiResponse<List<Views.BerthingView>> berthings(@PathVariable("id") Long id) {
        return ApiResponse.success(berthingService.listByVessel(id));
    }
}
