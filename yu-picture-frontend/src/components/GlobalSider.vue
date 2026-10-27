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
import { computed, h, ref, watch } from 'vue'
import { PictureOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue'
import { message, type MenuProps } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { listMyTeamSpaceUsingPost } from '@/api/spaceUserController.ts'
import { SPACE_TYPE_ENUM } from '@/constants/space.ts'

const loginUserStore = useLoginUserStore()
const router = useRouter()

/**
 * 固定菜单
 *
 * /my_space 是中间页，会按「有没有私有空间」再跳到空间详情或创建空间页；
 * 创建团队带 type=1，创建页据此预填 spaceType
 */
const fixedMenuItems: MenuProps['items'] = [
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
  {
    key: '/add_space?type=' + SPACE_TYPE_ENUM.TEAM,
    label: '创建团队',
    icon: () => h(TeamOutlined),
  },
]

/** 我加入的团队空间（后端 /spaceUser/list/my 返回 SpaceUserVO 列表） */
const teamSpaceList = ref<API.SpaceUserVO[]>([])

/**
 * 最终菜单 = 固定菜单 + 「我的团队」分组
 *
 * 没有团队空间时不展示空分组；分组里每个团队空间都指向 /space/{spaceId}
 */
const menuItems = computed<MenuProps['items']>(() => {
  // 只认能拼出空间信息的成员记录：后端 /spaceUser/list/my 已过滤一遍，
  // 前端再兜一层，避免空间删除后残留的成员记录在侧边栏显示成「未命名团队」
  const validTeamSpaceList = teamSpaceList.value.filter((spaceUser) => spaceUser.space != null)
  if (validTeamSpaceList.length < 1) {
    return fixedMenuItems
  }
  const teamSpaceSubMenus = validTeamSpaceList.map((spaceUser) => ({
    key: '/space/' + spaceUser.spaceId,
    label: spaceUser.space?.spaceName ?? '未命名团队',
  }))
  const teamSpaceMenuGroup = {
    type: 'group' as const,
    label: '我的团队',
    key: 'teamSpace',
    children: teamSpaceSubMenus,
  }
  return [...(fixedMenuItems ?? []), teamSpaceMenuGroup]
})

/**
 * 加载我加入的团队空间
 *
 * 失败只提示、不清空已有列表：网络抖一下不该把侧边栏的团队入口全弄没了
 */
const fetchTeamSpaceList = async () => {
  try {
    const res = await listMyTeamSpaceUsingPost()
    if (res.data.code === 0 && res.data.data) {
      teamSpaceList.value = res.data.data
    } else {
      message.error('加载我的团队空间失败，' + res.data.message)
    }
  } catch (error) {
    message.error('加载我的团队空间失败，' + (error as Error).message)
  }
}

/**
 * 登录状态变化时加载 / 清空
 *
 * immediate 用来兜住「组件挂载前首次导航已经结束」的情况：
 * 那时 afterEach 不会再触发，靠这里在拿到登录用户后补一次
 */
watch(
  () => loginUserStore.loginUser.id,
  (id) => {
    if (id) {
      fetchTeamSpaceList()
    } else {
      teamSpaceList.value = []
    }
  },
  { immediate: true },
)

// 当前选中菜单：用 fullPath 而不是 path，/add_space?type=1 这种带参数的菜单才能高亮
const current = ref<string[]>([])
// 监听路由变化，更新当前选中菜单（和 GlobalHeader 一样，首帧高亮由 afterEach 补上）
router.afterEach((to) => {
  current.value = [to.fullPath]
  // 每次导航后刷新团队列表：新建团队空间 / 被邀请加入后，侧边栏不用手动刷新就能出现
  if (loginUserStore.loginUser.id) {
    fetchTeamSpaceList()
  }
})

/**
 * 路由跳转事件
 *
 * 必须直接 push 完整字符串（保留 ?type=1），
 * 用 { path: key } 会把查询参数丢掉，创建团队就变成了创建私有空间
 */
const doMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push(String(key))
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
