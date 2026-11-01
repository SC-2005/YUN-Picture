import axios, { type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { message } from 'ant-design-vue'

// 开发环境直连本地后端；生产构建走同域 Nginx，由 Nginx 把 /api 反代到 8123
// 前后端分开部署时，在 .env.production 里写 VITE_API_BASE_URL=https://api.你的域名.com
const baseURL =
  import.meta.env.VITE_API_BASE_URL ??
  (import.meta.env.DEV ? 'http://localhost:8123' : '')

//创建Axios实例
const myAxios = axios.create({
  baseURL,
  timeout: 60000,
  withCredentials: true,
})

// 全局请求拦截器
myAxios.interceptors.request.use(
  function (config) {
    // Do something before request is sent
    return config
  },
  function (error) {
    // Do something with request error
    return Promise.reject(error)
  },
)

// 全局响应拦截器
myAxios.interceptors.response.use(
  function (response) {
    const { data } = response
    // 未登录
    if (data.code === 40100) {
      // 不是获取用户信息的请求，并且用户目前不是已经在用户登录页面，则跳转到登录页面
      if (
        !response.request.responseURL.includes('user/get/login') &&
        !window.location.pathname.includes('/user/login')
      ) {
        message.warning('请先登录')
        // 只传站内路径 + 查询参数：UserLoginPage 的守卫是 redirect.startsWith('/')，
        // 传完整的 window.location.href 会被判定成外链、直接丢弃，登录后就回不到原来的筛选条件了
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/user/login?redirect=${redirect}`
      }
    }
    return response
  },
  function (error) {
    // Any status codes that falls outside the range of 2xx cause this function to trigger
    // Do something with response error
    return Promise.reject(error)
  },
)

/**
 * @umijs/openapi 生成的代码里会带一个 umi-request 风格的 `requestType: 'form'`
 * （见 node_modules/@umijs/openapi/templates/serviceController.njk），axios 并没有这个配置项，
 * 类型对不上就会报 TS2353。它运行时是惰性的——上传 FormData 时浏览器会自动带上 multipart
 * 边界，本来也不需要它——所以这里在请求函数这一层把类型放宽，而不是去改生成的文件
 * （改了下次 npm run openapi 会被覆盖）。
 */
type RequestConfig = AxiosRequestConfig & { requestType?: string }

// 用 axios 实例自身的可调用签名 myAxios<T>(url, config)，
// 而不是 myAxios.request<T>(url, config) —— 后者在这个 axios 版本里只有单参重载
// 泛型默认值用 unknown 而不是 any：生成出来的接口函数每个都会显式传 T，默认值用不到，
// 而 any 会一直报 @typescript-eslint/no-explicit-any
const request = <T = unknown>(url: string, config?: RequestConfig): Promise<AxiosResponse<T>> =>
  myAxios<T>(url, config)

export default request
