package com.ftllight.flashsalebackend.controller;

import com.ftllight.flashsalebackend.common.Result;
import com.ftllight.flashsalebackend.dto.GoodsDTO;
import com.ftllight.flashsalebackend.service.GoodsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/goods")
@RequiredArgsConstructor
public class GoodsController {

    private final GoodsService goodsService;

    @GetMapping
    public Result<List<GoodsDTO>> listGoods() {
        return Result.success(goodsService.listGoods());
    }

    @GetMapping("/{goodsId}")
    public Result<GoodsDTO> getGoods(@PathVariable Long goodsId) {
        return Result.success(goodsService.getGoodsById(goodsId));
    }
}
