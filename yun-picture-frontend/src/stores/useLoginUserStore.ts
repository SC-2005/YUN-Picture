import { ref } from 'vue'
import { defineStore } from 'pinia'
import { getLoginUserUsingGet } from '@/api/userController.ts'

/**
 * 存储用户登录信息的状态
 */
export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<API.LoginUserVO>({
    userName: '未登录',
  })

  // 记录正在进行的请求，避免并发重复请求（比如页面加载和路由守卫同时调用）
  let fetchPromise: Promise<void> | null = null

  /**
   * 远程获取登录用户信息
   */
  function fetchLoginUser(): Promise<void> {
    if (fetchPromise) {
      return fetchPromise
    }
    fetchPromise = getLoginUserUsingGet()
      .then((res) => {
        if (res.data.code === 0 && res.data.data) {
          loginUser.value = res.data.data
        }
      })
      .finally(() => {
        fetchPromise = null
      })
    return fetchPromise
  }

  /**
   * 设置登录用户
   * @param newLoginUser
   */
  function setLoginUser(newLoginUser: API.LoginUserVO): void {
    loginUser.value = newLoginUser
  }


  return { loginUser, fetchLoginUser, setLoginUser }
})
