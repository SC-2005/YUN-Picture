<template>
  <!-- 未登录不展示侧边栏：公共图库不需要登录，但「我的空间」点了也只会被路由守卫弹回登录页 -->
  <a-layout-sider
    v-if="loginUserStore.loginUser.id"
    class="sider"
    width="200"
    breakpoint="lg"
    :collapsed-width="0"
  >
    <a-menu v-model:selectedKeys="current" mode="inline" :items="menuItems" @click="doMenuClick" />
  </a-layout-sider>
</template>

<script setup lang="ts">
import { h, ref } from 'vue'
import { PictureOutlined, UserOutlined } from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'

const loginUserStore = useLoginUserStore()

// 菜单列表。/my_space 是中间页，会按「有没有空间」再跳到空间详情或创建空间页
const menuItems = [
  {
    key: '/',
    label: '公共图库',
    icon: () => h(PictureOutlined),
  },
  {
    key: '/my_space',
    label: '我的空间',
    icon: () => h(UserOutlined),
  },
]

const router = useRouter()

// 当前选中菜单
const current = ref<string[]>([])
// 监听路由变化，更新当前选中菜单（和 GlobalHeader 一样，首帧高亮由 afterEach 补上）
router.afterEach((to) => {
  current.value = [to.path]
})

// 路由跳转事件
const doMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push({ path: String(key) })
}
</script>

<style scoped>
/* a-layout-sider 默认是深色背景，这里压成白色，和顶部栏连成一体 */
.sider {
  background: #fff;
  padding-top: 20px;
  border-right: 0.5px solid #eee;
  /* 滚动页面时只有右侧图片区在动，左侧菜单一直粘在视口里。
     整个页面的滚动条挂在 window 上（外层 a-layout 只是 min-height: 100vh），
     所以 sticky 不用改任何布局结构就能生效。
     必须显式给高度：不作为 flex 子项时它会被拉伸到整列高度（和内容一样高），
     那样元素和滚动区域等高，sticky 就没有可粘的余地了。
     top 用 0 而不是 64px（顶部栏高度）：页面滚过顶部栏后才会开始粘住，
     那时顶部栏已经完全滚出屏幕，不会出现「侧栏盖住顶部栏」的画面 */
  position: sticky;
  top: 0;
  height: 100vh;
  /* 菜单项以后变多时让侧栏自己滚，不至于把整列撑高 */
  overflow-y: auto;
}

/* 内嵌菜单自带的右边框在白色侧边栏上会形成双线 */
.sider :deep(.ant-menu-root) {
  border-inline-end: none !important;
}
</style>
