<template>
  <div id="mySpacePage">
    <p>正在跳转，请稍候。。。</p>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { listSpaceVoByPageUsingPost } from '@/api/spaceController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'

const router = useRouter()
const loginUserStore = useLoginUserStore()

/**
 * 检查用户是否已有私有空间
 *
 * 这是个中间页：用户未登录去登录页，有空间进第一个空间，没有空间去创建空间页。
 * 全部用 replace 而不是 push —— 点浏览器后退时不该再回到这个「正在跳转」的中间页
 */
const checkUserSpace = async () => {
  const loginUser = loginUserStore.loginUser
  if (!loginUser?.id) {
    router.replace('/user/login')
    return
  }
  // 获取该用户已创建的空间。pageSize 给 1 就够：每个用户最多只能创建一个私有空间
  const res = await listSpaceVoByPageUsingPost({
    userId: loginUser.id,
    current: 1,
    pageSize: 1,
  })
  if (res.data.code !== 0) {
    message.error('加载我的空间失败，' + res.data.message)
    return
  }
  const records = res.data.data?.records ?? []
  if (records.length > 0) {
    // id 是雪花 id 的字符串形态，直接拼路径，不要做数值转换
    router.replace(`/space/${records[0].id}`)
  } else {
    router.replace('/add_space')
    message.warning('请先创建空间')
  }
}

onMounted(() => {
  checkUserSpace()
})
</script>
