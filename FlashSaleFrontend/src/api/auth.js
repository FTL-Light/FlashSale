import request from './request'

export const login = data => request.post('/user/login', data)
