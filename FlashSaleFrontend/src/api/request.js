import axios from 'axios'

// 通过 Vite 代理访问后端（后端无 CORS 配置）
const instance = axios.create({ baseURL: '/api' })

instance.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

instance.interceptors.response.use(res => {
  const body = res.data
  if (body.code !== 0) {
    if (body.code === 500102) {
      localStorage.removeItem('token')
    }
    alert(body.msg)
    return Promise.reject(new Error(body.msg))
  }
  return body.data
})

export default instance
