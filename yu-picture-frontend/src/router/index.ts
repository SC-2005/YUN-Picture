import { createRouter, createWebHistory } from 'vue-router'
import HomePage from '@/pages/HomePage.vue'
import UserLoginPage from '@/pages/user/UserLoginPage.vue'
import UserRegisterPage from '@/pages/user/UserRegisterPage.vue'
import UserManagePage from '@/pages/admin/UserManagePage.vue'
import AddPicturePage from '@/pages/AddPicturePage.vue'
import AddPictureBatchPage from '@/pages/AddPictureBatchPage.vue'
import PictureManagePage from '@/pages/admin/PictureManagePage.vue'
import PictureDetailPage from '@/pages/PictureDetailPage.vue'
import SpaceManagePage from '@/pages/admin/SpaceManagePage.vue'
import AddSpacePage from '@/pages/AddSpacePage.vue'
import MySpacePage from '@/pages/MySpacePage.vue'
import SpaceDetailPage from '@/pages/SpaceDetailPage.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  /**
   * savedPosition 只在浏览器前进/后退时才有值，其他导航一律回到顶部。
   * 首页还会在 HomePage 里用缓存的列表把滚动位置再校正一次 ——
   * 第一次进入首页时列表要异步加载，这一层先滚可能滚不到底。
   */
  scrollBehavior: (_to, _from, savedPosition) => savedPosition ?? { top: 0, left: 0 },
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomePage,
    },
    {
      path: '/user/login',
      name: '用户登录',
      component: UserLoginPage,
    },
    {
      path: '/user/register',
      name: '用户注册',
      component: UserRegisterPage,
    },
    {
      path: '/admin/userManage',
      name: '用户管理',
      component: UserManagePage,
      // access: 该页面需要的权限。admin 表示需要登录且是管理员(由 src/access.ts 全局守卫统一校验)
      meta: { access: 'admin' },
    },
    {
      path: '/admin/pictureManage',
      name: '图片管理',
      component: PictureManagePage,
      // 后端 listPictureByPage / updatePicture 都要求管理员
      meta: { access: 'admin' },
    },
    {
      path: '/add_picture',
      name: '创建图片',
      component: AddPicturePage,
      // 不限制角色：后端 /picture/upload 已放开为「登录即可」，
      // 非管理员上传的图片会进入待审核，审核通过前不出现在首页
    },
    {
      path: '/add_picture/batch',
      name: '批量创建图片',
      component: AddPictureBatchPage,
      // 后端 /picture/upload/batch 带 @AuthCheck(mustRole = ADMIN_ROLE)，仅管理员可用
      meta: { access: 'admin' },
    },
    {
      path: '/picture/:id',
      name: '图片详情',
      component: PictureDetailPage,
      // 把路由参数 id 作为 props 传给页面组件
      props: true,
    },
    {
      path: '/admin/spaceManage',
      name: '空间管理',
      component: SpaceManagePage,
      // 后端 listSpaceByPage / updateSpace 都要求管理员
      meta: { access: 'admin' },
    },
    {
      path: '/add_space',
      name: '创建空间',
      component: AddSpacePage,
      // 不限制角色：普通用户也能创建空间（但只能创建普通版，且只能有一个）
    },
    {
      path: '/my_space',
      name: '我的空间',
      component: MySpacePage,
      // 中间页：未登录会被页面自己 replace 到登录页
    },
    {
      path: '/space/:id',
      name: '空间详情',
      component: SpaceDetailPage,
      // 把路由参数 id 作为 props 传给页面组件
      props: true,
    },
    {
      path: '/about',
      name: 'about',
      // route level code-splitting
      // this generates a separate chunk (About.[hash].js) for this route
      // which is lazy-loaded when the route is visited.
      component: () => import('../views/AboutView.vue'),
    },
  ],
})

export default router
