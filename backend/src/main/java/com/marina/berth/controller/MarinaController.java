package com.marina.berth.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marina.berth.common.ApiResponse;
import com.marina.berth.dto.Views;
import com.marina.berth.service.MarinaService;

/** 码头总览：顶栏计数器。 */
@RestController
@RequestMapping("/api/marina")
public class MarinaController {

    private final MarinaService marinaService;

    public MarinaController(MarinaService marinaService) {
        this.marinaService = marinaService;
    }

    @GetMapping("/overview")
    public ApiResponse<Views.Overview> overview() {
        return ApiResponse.success(marinaService.overview());
    }
}
