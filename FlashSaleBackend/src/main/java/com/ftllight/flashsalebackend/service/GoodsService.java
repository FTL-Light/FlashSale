package com.ftllight.flashsalebackend.service;

import com.ftllight.flashsalebackend.dto.GoodsDTO;

import java.util.List;

public interface GoodsService {

    List<GoodsDTO> listGoods();

    GoodsDTO getGoodsById(Long goodsId);
}
