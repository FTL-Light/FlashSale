package com.ftllight.flashsalebackend.dto;

import com.ftllight.flashsalebackend.entity.Goods;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class GoodsDTO {

    private Long id;

    private String name;

    private String title;

    private String image;

    private BigDecimal price;

    private BigDecimal seckillPrice;

    private Integer stockCount;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer seckillStatus;

    public static GoodsDTO from(Goods goods) {
        GoodsDTO dto = new GoodsDTO();
        dto.setId(goods.getId());
        dto.setName(goods.getName());
        dto.setTitle(goods.getTitle());
        dto.setImage(goods.getImage());
        dto.setPrice(goods.getPrice());
        dto.setSeckillPrice(goods.getSeckillPrice());
        dto.setStockCount(goods.getStockCount());
        dto.setStartTime(goods.getStartTime());
        dto.setEndTime(goods.getEndTime());

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(goods.getStartTime())) {
            dto.setSeckillStatus(0);
        } else if (now.isAfter(goods.getEndTime())) {
            dto.setSeckillStatus(2);
        } else {
            dto.setSeckillStatus(1);
        }
        return dto;
    }
}
