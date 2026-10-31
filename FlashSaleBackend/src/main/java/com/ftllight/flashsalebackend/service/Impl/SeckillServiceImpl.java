package com.ftllight.flashsalebackend.service.Impl;

import com.ftllight.flashsalebackend.common.CodeMsg;
import com.ftllight.flashsalebackend.common.GlobalException;
import com.ftllight.flashsalebackend.common.RedisTypeString;
import com.ftllight.flashsalebackend.dto.SeckillResultDTO;
import com.ftllight.flashsalebackend.entity.Goods;
import com.ftllight.flashsalebackend.mapper.GoodsMapper;
import com.ftllight.flashsalebackend.mq.SeckillMessage;
import com.ftllight.flashsalebackend.mq.SeckillProducer;
import com.ftllight.flashsalebackend.service.SeckillService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private static final long LOCK_TTL_SECONDS = 30 * 60;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT =
        new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 2
            end
            local stock = tonumber(redis.call('GET', KEYS[1]))
            if stock == nil or stock <= 0 then
                redis.call('SET', KEYS[2], '1')
                return 3
            end
            local locked = redis.call('SET', KEYS[3], '1', 'NX', 'EX', tonumber(ARGV[1]))
            if not locked then
                return 4
            end
            local left = redis.call('DECR', KEYS[1])
            if left == 0 then
                redis.call('SET', KEYS[2], '1')
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT =
        new DefaultRedisScript<>("""
            redis.call('INCR', KEYS[1])
            redis.call('DEL', KEYS[2])
            redis.call('DEL', KEYS[3])
            return 1
            """, Long.class);

    private final GoodsMapper goodsMapper;
    private final SeckillProducer seckillProducer;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @PostConstruct
    public void initStock() {
        List<Goods> goodsList = goodsMapper.selectList(null);
        for (Goods goods : goodsList) {
            String stockKey = RedisTypeString.STOCK_PREFIX.key(goods.getId());
            String soldOutKey = RedisTypeString.SOLD_OUT_PREFIX.key(goods.getId());
            stringRedisTemplate.opsForValue()
                    .set(stockKey, String.valueOf(goods.getStockCount()));
            stringRedisTemplate.delete(soldOutKey);
        }
        log.info("秒杀库存预热完成，商品数: {}", goodsList.size());
    }

    @Override
    public String seckill(Long userId, Long goodsId) {
        Goods goods = goodsMapper.selectById(goodsId);
        if (goods == null) throw new GlobalException(CodeMsg.PARAM_ERROR);

        LocalDateTime now = LocalDateTime.now();
        if (goods.getStartTime().isAfter(now))
            throw new GlobalException(CodeMsg.SECKILL_NOT_STARTED);
        if (goods.getEndTime().isBefore(now))
            throw new GlobalException(CodeMsg.SECKILL_ENDED);

        String stockKey = RedisTypeString.STOCK_PREFIX.key(goodsId);
        String soldOutKey = RedisTypeString.SOLD_OUT_PREFIX.key(goodsId);
        String lockKey = RedisTypeString.USER_ORDER_PREFIX.key(goodsId, userId);

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                List.of(stockKey, soldOutKey, lockKey),
                String.valueOf(LOCK_TTL_SECONDS));

        if (result == null) throw new GlobalException(CodeMsg.SERVER_ERROR);
        if (result == 2L) throw new GlobalException(CodeMsg.SECKILL_SOLD_OUT);
        if (result == 3L) throw new GlobalException(CodeMsg.STOCK_EMPTY);
        if (result == 4L) throw new GlobalException(CodeMsg.SECKILL_REPEATED);

        try {
            seckillProducer.send(new SeckillMessage(userId, goodsId));
        } catch (Exception e) {
            stringRedisTemplate.execute(
                    ROLLBACK_SCRIPT, List.of(stockKey, lockKey, soldOutKey));
            throw new GlobalException(CodeMsg.SERVER_ERROR);
        }
        return "购买成功!正在排队...";
    }

    @Override
    public SeckillResultDTO queryResult(Long userId, Long goodsId) {
        String resultKey = RedisTypeString.RESULT_PREFIX.key(goodsId, userId);
        String result = stringRedisTemplate.opsForValue().get(resultKey);
        if (StringUtils.hasText(result)) {
            return new SeckillResultDTO(Long.valueOf(result));
        }
        return new SeckillResultDTO(-1L);
    }
}
