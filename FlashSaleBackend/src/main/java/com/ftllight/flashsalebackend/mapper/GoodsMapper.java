package com.ftllight.flashsalebackend.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ftllight.flashsalebackend.entity.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    @Update("UPDATE goods SET stock_count = stock_count - 1 " +
            "WHERE id = #{goodsId} AND stock_count > 0")
    int decrStock(@Param("goodsId") Long goodsId);
}
