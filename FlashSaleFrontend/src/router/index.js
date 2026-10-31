import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import GoodsList from '../views/GoodsList.vue'
import OrderResult from '../views/OrderResult.vue'

const routes = [
  { path: '/', redirect: '/goods' },
  { path: '/login', component: LoginView },
  { path: '/goods', component: GoodsList },
  { path: '/order/:goodsId', component: OrderResult }
]

export default createRouter({
  history: createWebHistory(),
  routes
})
