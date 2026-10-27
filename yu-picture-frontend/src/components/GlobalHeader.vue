<template>
  <div id="globalHeader">
    <a-row :wrap="false">
      <a-col flex="200px">
        <router-link to="/">
          <div class="title-bar">
            <img class="logo" src="../assets/logo.png" alt="logo" />
            <div class="title">图协云</div>
          </div>
        </router-link>
      </a-col>

      <a-col flex="auto">
        <a-menu
          v-model:selectedKeys="current"
          mode="horizontal"
          :items="items"
          @click="doMenuClick"
        />
      </a-col>
      <!-- 用户信息展示栏 -->
      <a-col flex="120px">
        <div class="user-login-status">
          <div v-if="loginUserStore.loginUser.id" class="user-info">
            <a-dropdown>
              <a-space>
                <!-- 有真实头像时展示图片，否则用名字的第一个字作为默认头像 -->
                <a-avatar
                  :src="loginUserStore.loginUser.userAvatar || undefined"
                  :style="avatarStyle"
                >
                  {{ avatarText }}
                </a-avatar>
                <span>{{ displayName }}</span>
              </a-space>
              <template #overlay>
                <a-menu>
                  <a-menu-item>
                    <router-link to="/my_space">
                      <UserOutlined />
                      我的空间
                    </router-link>
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
  </div>
</template>
<script lang="ts" setup>
import { computed, h, ref } from 'vue'
import { HomeOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons-vue'
import { type MenuProps, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { userLogoutUsingPost } from '@/api/userController.ts'

const loginUserStore = useLoginUserStore()

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

const router = useRouter()

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
}

.logo {
  height: 48px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
