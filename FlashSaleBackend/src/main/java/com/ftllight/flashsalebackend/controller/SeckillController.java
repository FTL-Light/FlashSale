package com.ftllight.flashsalebackend.controller;

import com.ftllight.flashsalebackend.common.Result;
import com.ftllight.flashsalebackend.dto.SeckillResultDTO;
import com.ftllight.flashsalebackend.interceptor.LoginInterceptor;
import com.ftllight.flashsalebackend.service.SeckillService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SeckillController {

    private final SeckillService seckillService;

    @PostMapping("/seckill/{goodsId}")
    public Result<String> seckill(@PathVariable Long goodsId) {
        Long userId = LoginInterceptor.getCurrentUserId();
        return Result.success(seckillService.seckill(userId, goodsId));
    }

    @GetMapping("/order/result/{goodsId}")
    public Result<SeckillResultDTO> queryResult(@PathVariable Long goodsId) {
        Long userId = LoginInterceptor.getCurrentUserId();
        return Result.success(seckillService.queryResult(userId, goodsId));
    }
}
