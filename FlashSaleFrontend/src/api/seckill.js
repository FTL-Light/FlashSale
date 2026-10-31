import request from './request'

export const seckill = goodsId => request.post(`/seckill/${goodsId}`)

export const queryResult = goodsId => request.get(`/order/result/${goodsId}`)
