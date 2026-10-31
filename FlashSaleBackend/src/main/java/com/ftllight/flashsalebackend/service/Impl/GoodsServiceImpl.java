package com.ftllight.flashsalebackend.service.Impl;

import com.ftllight.flashsalebackend.common.CodeMsg;
import com.ftllight.flashsalebackend.common.GlobalException;
import com.ftllight.flashsalebackend.dto.GoodsDTO;
import com.ftllight.flashsalebackend.entity.Goods;
import com.ftllight.flashsalebackend.mapper.GoodsMapper;
import com.ftllight.flashsalebackend.service.GoodsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GoodsServiceImpl implements GoodsService {

    private final GoodsMapper goodsMapper;

    @Override
    public List<GoodsDTO> listGoods() {
        return goodsMapper.selectList(null).stream()
                .map(GoodsDTO::from)
                .toList();
    }

    @Override
    public GoodsDTO getGoodsById(Long goodsId) {
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) throw new GlobalException(CodeMsg.PARAM_ERROR);
        return GoodsDTO.from(goods);
    }
}
