import router from "@/router";
import {useLoginUserStore} from "@/stores/useLoginUserStore.ts";
import { message } from 'ant-design-vue'

// 是否为本次页面加载后的首次校验(首次需要向后端拉取当前登录用户)
let firstCheck: boolean = true

/**
 * 全局权限校验:每次路由跳转都会执行。
 * 规则(配合路由 meta.access 使用):
 *  - 公开页面:直接放行(首次进入仍会拉取登录用户,保证顶部栏能正确展示登录态);
 *  - 需要 admin 的页面(/admin/userManage):仅管理员能进入;
 *  - 未登录或普通用户访问时,提示「没有权限」并留在当前页面(首次直达受保护页时退回首页)。
 */
router.beforeEach(async (to, from) => {
  const loginUserStore = useLoginUserStore();

  // 首次进入任意页面时拉取一次当前登录用户;
  // 若访问受保护页但本地还没有用户信息(如刷新/深链直达),也再向后端确认一次登录态
  if (firstCheck || (!loginUserStore.loginUser.id && to.meta.access)) {
    await loginUserStore.fetchLoginUser();
    firstCheck = false;
  }

  // 该页面要求的权限(未配置则公开访问)
  const needAccess = to.meta.access as string | undefined
  if (!needAccess) {
    return true
  }

  const loginUser = loginUserStore.loginUser

  // 未登录:提示无权限,留在当前页面(避免把已登录普通用户错误送回登录页)
  if (!loginUser.id) {
    message.error('没有权限，请先登录')
    // 已有来源页则留在原页面;若是直接输入地址首次进入受保护页(没有上一页可停留),退回首页防止空白
    return from.name ? false : { path: '/', replace: true }
  }

  // 已登录但不是管理员
  if (needAccess === 'admin' && loginUser.userRole !== 'admin') {
    message.error('没有权限，仅管理员可访问')
    return from.name ? false : { path: '/', replace: true }
  }

  return true
})
