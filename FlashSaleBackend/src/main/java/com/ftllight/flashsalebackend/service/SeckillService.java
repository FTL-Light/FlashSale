package com.ftllight.flashsalebackend.service;

import com.ftllight.flashsalebackend.dto.SeckillResultDTO;

public interface SeckillService {

    void initStock();

    String seckill(Long userId, Long goodsId);

    SeckillResultDTO queryResult(Long userId, Long goodsId);
}
