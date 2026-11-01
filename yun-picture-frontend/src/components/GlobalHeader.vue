<template>
  <div id="globalHeader">
    <a-row :wrap="true">
      <a-col class="title-col">
        <router-link to="/">
          <div class="title-bar">
            <img class="logo" src="../assets/logo.png" alt="logo" />
            <div class="title">图片分享-浮光图记</div>
          </div>
        </router-link>
      </a-col>

      <a-col class="menu-col">
        <a-menu
          v-model:selectedKeys="current"
          mode="horizontal"
          :items="items"
          @click="doMenuClick"
        />
      </a-col>
      <!-- 用户信息展示栏 -->
      <a-col class="user-col">
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id" class="user-info">
            <!--
              用 click 而不是纯 hover：触屏设备没有 hover，点一下只会触发一次
              mouseenter，随后链接就把页面跳走了，下拉菜单根本来不及展开。
              触发元素也不再是 router-link —— 点头像只负责展开菜单，
              个人中心走菜单里的入口。
            -->
            <a-dropdown :trigger="['hover', 'click']">
              <div class="user-trigger" role="button" tabindex="0">
                <!-- 有真实头像时展示图片，否则用名字的第一个字作为默认头像 -->
                <a-avatar
                  :src="loginUserStore.loginUser.userAvatar || undefined"
                  :style="avatarStyle"
                >
                  {{ avatarText }}
                </a-avatar>
                <span>{{ displayName }}</span>
              </div>
              <template #overlay>
                <a-menu>
                  <a-menu-item>
                    <router-link to="/user/center">
                      <UserOutlined />
                      个人中心
                    </router-link>
                  </a-menu-item>
                  <a-menu-item>
                    <router-link to="/my_space">
                      <UserOutlined />
                      我的空间
                    </router-link>
                  </a-menu-item>
                  <!--
                    团队空间在桌面端靠左侧边栏进入，手机端侧边栏会自动收起，
                    所以这里再给一个入口，点开抽屉列出我加入的所有团队。
                  -->
                  <a-menu-item key="teamSpace" @click="openTeamDrawer">
                    <TeamOutlined />
                    团队空间
                  </a-menu-item>
                  <a-menu-item @click="doLogout">
                    <LogoutOutlined />
                    退出登录
                  </a-menu-item>
                </a-menu>
              </template>
            </a-dropdown>
          </div>
          <div v-else>
            <a-button type="primary" href="/user/login">登录</a-button>
          </div>
        </div>
      </a-col>
    </a-row>

    <!-- 手机端侧边栏收起后，团队空间从这里进 -->
    <a-drawer
      v-model:open="teamDrawerOpen"
      title="我的团队空间"
      placement="left"
      :width="300"
    >
      <a-spin :spinning="teamLoading">
        <a-empty v-if="teamSpaceList.length === 0" description="还没有加入团队空间" />
        <a-list v-else :data-source="teamSpaceList" size="small">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-button type="link" style="padding: 0" @click="goSpace(item.spaceId)">
                {{ item.space?.spaceName ?? '未命名团队' }}
              </a-button>
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
      <a-button type="primary" block style="margin-top: 16px" @click="goCreateTeam">
        + 创建团队
      </a-button>
    </a-drawer>
  </div>
</template>
<script lang="ts" setup>
import { computed, h, ref } from 'vue'
import { HomeOutlined, LogoutOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons-vue'
import { type MenuProps, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { userLogoutUsingPost } from '@/api/userController.ts'
import { listMyTeamSpaceUsingPost } from '@/api/spaceUserController.ts'
import { SPACE_TYPE_ENUM } from '@/constants/space.ts'

const loginUserStore = useLoginUserStore()
const router = useRouter()

/** 团队空间抽屉 */
const teamDrawerOpen = ref(false)
const teamLoading = ref(false)
const teamSpaceList = ref<API.SpaceUserVO[]>([])

/**
 * 加载我加入的团队空间
 *
 * 和 GlobalSider 用的是同一个接口，这里只负责手机端的入口。
 * 过滤掉 space 为空的记录：空间删除后残留的成员记录拼不出可跳转的地址。
 */
const fetchTeamSpaceList = async () => {
  teamLoading.value = true
  try {
    const res = await listMyTeamSpaceUsingPost()
    if (res.data.code === 0) {
      teamSpaceList.value = (res.data.data ?? []).filter((item) => item.space != null)
    } else {
      message.error('加载团队空间失败，' + res.data.message)
    }
  } catch (error) {
    message.error('加载团队空间失败，' + (error as Error).message)
  } finally {
    teamLoading.value = false
  }
}

const openTeamDrawer = () => {
  teamDrawerOpen.value = true
  fetchTeamSpaceList()
}

const goSpace = (spaceId?: string | number) => {
  if (spaceId == null) {
    return
  }
  teamDrawerOpen.value = false
  router.push(`/space/${spaceId}`)
}

const goCreateTeam = () => {
  teamDrawerOpen.value = false
  router.push('/add_space?type=' + SPACE_TYPE_ENUM.TEAM)
}

// 展示名：优先取用户名，其次账号，兜底
const displayName = computed(() => {
  const user = loginUserStore.loginUser
  return user.userName || user.userAccount || '无名'
})

// 默认头像文字：取展示名的第一个字符
const avatarText = computed(() => displayName.value.slice(0, 1).toUpperCase())

// 默认头像配色：根据名字简单散列从固定色板中取色，避免同名/同色
const avatarColorList = ['#1677ff', '#eb2f96', '#722ed1', '#13c2c2', '#52c41a', '#fa541c']
const avatarStyle = computed(() => {
  const name = displayName.value
  let hash = 0
  for (let i = 0; i < name.length; i++) {
    hash = (hash * 31 + name.charCodeAt(i)) % 100000
  }
  return {
    backgroundColor: avatarColorList[hash % avatarColorList.length],
    color: '#fff',
    verticalAlign: 'middle',
  }
})

// 顶部菜单：首页 / 创建图片 / 开源地址人人可见，「图片管理 / 用户管理」仅管理员可见
// （这两个页面对应的后端接口都带 @AuthCheck(mustRole = ADMIN_ROLE)，普通用户点了也是 403）
// 创建图片不限制角色：后端 /picture/upload 只要求登录，
// 非管理员上传的图片会进入待审核，审核通过前不出现在首页
const items = computed<MenuProps['items']>(() => [
  {
    key: '/',
    icon: () => h(HomeOutlined),
    label: '首页',
    title: '主页',
  },
  {
    key: '/add_picture',
    label: '创建图片',
    title: '创建图片',
  },
  ...(loginUserStore.loginUser.userRole === 'admin'
    ? [
        {
          key: '/admin/pictureManage',
          label: '图片管理',
          title: '图片管理',
        },
        {
          key: '/admin/userManage',
          label: '用户管理',
          title: '用户管理',
        },
        {
          key: '/admin/spaceManage',
          label: '空间管理',
          title: '空间管理',
        },
      ]
    : []),
  {
    key: 'others',
    label: h(
      'a',
      { href: 'https://github.com/SC-2005/YU-Picture', target: '_blank', rel: 'noopener noreferrer' },
      '开源地址',
    ),
    title: '开源地址',
  },
])

// 当前要高亮的菜单项
const current = ref<string[]>([])
//监听路由变化,更新高亮菜单项
router.afterEach((to) => {
  current.value = [to.path]
})

//路由跳转事件
const doMenuClick: MenuProps['onClick'] = ({ key }) => {
  router.push({ path: String(key) })
}

// 退出登录
const doLogout = async () => {
  const res = await userLogoutUsingPost()
  if (res.data.code === 0) {
    loginUserStore.setLoginUser({
      userName: '未登录',
    })
    message.success('退出登录成功')
    await router.push({
      path: '/user/login',
    })
  } else {
    message.error('退出登录失败' + res.data.message)
  }
}
</script>

<style scoped>
/*
 * 三列宽度放在 CSS 里而不是 a-col 的 flex 属性上：
 * 手机端要改成「站名 + 头像一行，菜单独占第二行」，
 * 写在模板里的 flex 会变成行内样式，媒体查询覆盖不掉。
 */
#globalHeader .title-col {
  flex: 0 0 240px;
}

#globalHeader .menu-col {
  flex: 1 1 auto;
  /* 不设 min-width: 0 的话，菜单的固有宽度会把这一列撑开，横向溢出 */
  min-width: 0;
}

#globalHeader .user-col {
  flex: 0 0 120px;
}

#globalHeader .title-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 60px;
  padding-top: 8px;
  transform: translateX(-20px);
}

.title {
  color: black;
  font-size: 18px;
  margin-left: 16px;
  /* 站名变长后不要换行，否则会把 60px 高的标题栏撑开 */
  white-space: nowrap;
}

.logo {
  height: 48px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  color: inherit;
  cursor: pointer;
}

/*
 * 手机端：站名和头像占第一行，菜单换到第二行占满宽度。
 * 横向菜单自身会把放不下的项收进「...」，所以 5 个以上菜单项也不会溢出。
 */
@media (max-width: 768px) {
  #globalHeader .title-col {
    flex: 0 0 auto;
  }

  #globalHeader .user-col {
    flex: 0 0 auto;
    margin-left: auto;
  }

  #globalHeader .menu-col {
    flex: 1 1 100%;
    order: 3;
    margin-top: 4px;
  }

  #globalHeader .title {
    font-size: 16px;
    margin-left: 8px;
  }

  #globalHeader .title-bar {
    /* 桌面端为了视觉居中做的左移，窄屏下会把 logo 顶出屏幕 */
    transform: none;
  }

  #globalHeader .logo {
    height: 40px;
  }
}
</style>
