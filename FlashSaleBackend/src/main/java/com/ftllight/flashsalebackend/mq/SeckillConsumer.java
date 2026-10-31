package com.ftllight.flashsalebackend.mq;

import com.ftllight.flashsalebackend.common.RedisTypeString;
import com.ftllight.flashsalebackend.config.RabbitMQConfig;
import com.ftllight.flashsalebackend.entity.Goods;
import com.ftllight.flashsalebackend.entity.OrderInfo;
import com.ftllight.flashsalebackend.entity.SeckillOrder;
import com.ftllight.flashsalebackend.mapper.GoodsMapper;
import com.ftllight.flashsalebackend.mapper.OrderInfoMapper;
import com.ftllight.flashsalebackend.mapper.SeckillOrderMapper;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillConsumer {

    private final GoodsMapper goodsMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final StringRedisTemplate stringRedisTemplate;

    @RabbitListener(queues = RabbitMQConfig.SECKILL_QUEUE, ackMode = "MANUAL")
    public void handleSeckillMessage(SeckillMessage message, Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        Long userId = message.getUserId();
        Long goodsId = message.getGoodsId();
        try {
            Goods goods = goodsMapper.selectById(goodsId);
            if (goods == null) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            int rows = goodsMapper.decrStock(goodsId);
            if (rows <= 0) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            OrderInfo orderInfo = new OrderInfo();
            orderInfo.setUserId(userId);
            orderInfo.setGoodsId(goodsId);
            orderInfo.setGoodsName(goods.getName());
            orderInfo.setGoodsCount(1);
            orderInfo.setGoodsPrice(goods.getSeckillPrice());
            orderInfo.setStatus(0);
            orderInfo.setCreateTime(LocalDateTime.now());
            orderInfoMapper.insert(orderInfo);

            SeckillOrder seckillOrder = new SeckillOrder();
            seckillOrder.setUserId(userId);
            seckillOrder.setGoodsId(goodsId);
            seckillOrder.setOrderId(orderInfo.getId());
            seckillOrder.setCreateTime(LocalDateTime.now());
            seckillOrderMapper.insert(seckillOrder);

            String resultKey = RedisTypeString.RESULT_PREFIX.key(goodsId, userId);
            stringRedisTemplate.opsForValue()
                    .set(resultKey, String.valueOf(orderInfo.getId()), 30, TimeUnit.MINUTES);

            String lockKey = RedisTypeString.USER_ORDER_PREFIX.key(goodsId, userId);
            stringRedisTemplate.opsForValue()
                    .set(lockKey, String.valueOf(orderInfo.getId()), 30, TimeUnit.MINUTES);

            channel.basicAck(deliveryTag, false);
            log.info("秒杀订单落库成功: orderId={}, userId={}, goodsId={}",
                    orderInfo.getId(), userId, goodsId);
        } catch (Exception e) {
            log.error("秒杀消息消费失败, userId={}, goodsId={}", userId, goodsId, e);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException ex) {
                log.error("NACK 失败", ex);
            }
        }
    }
}
