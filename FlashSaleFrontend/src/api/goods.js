import request from './request'

export const listGoods = () => request.get('/goods')

export const getGoods = goodsId => request.get(`/goods/${goodsId}`)
