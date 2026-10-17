<template>
  <div id="UserLoginPage">
    <h2 class="title">图协云 - 用户登录</h2>
    <div class="desc">AI协同图片管理平台</div>
    <a-form :model="formState" name="basic" autocomplete="off" @finish="handleSubmit">
      <a-form-item name="userAccount" :rules="[{ required: true, message: '请输入账号' }]">
        <a-input size="large" v-model:value="formState.userAccount" placeholder="请输入账号" />
      </a-form-item>

      <a-form-item
        name="userPassword"
        :rules="[
          { required: true, message: '请输入密码' },
          { min: 8, message: '密码长度不能少于8位' },
        ]"
      >
        <a-input-password size="large" v-model:value="formState.userPassword" placeholder="请输入密码" />
      </a-form-item>
      <div class="tips">
        没有账号？
        <RouterLink to="/user/register">去注册</RouterLink>
      </div>
      <a-form-item>
        <a-button type="primary" size="large" html-type="submit" style="width: 100%">登录</a-button>
      </a-form-item>
    </a-form>
  </div>
</template>
<script lang="ts" setup>
import { reactive } from 'vue'
import { useRoute } from 'vue-router'
import { userLoginUsingPost } from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { message } from 'ant-design-vue'
import router from '@/router'

//用于接收表单输入的值
const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

const loginUserStore = useLoginUserStore()
const route = useRoute()

/**
 * 表单提交
 * @param values
 */
const handleSubmit = async (values: API.UserLoginRequest) => {
  try {
    const res = await userLoginUsingPost(values)
    //登录成功,把登录态保存到全局状态中
    if (res.data.code === 0 && res.data.data) {
      await loginUserStore.fetchLoginUser()
      message.success('登录成功')
      // 取出被拦截页面留下的 redirect 参数，登录成功后跳回原页面（只允许站内路径，防止开放重定向）
      const redirect =
        typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
          ? route.query.redirect
          : '/'
      router.replace(redirect)
    } else {
      message.error('登陆失败' + res.data.message)
    }
  }catch (e) {
    message.error('登录失败' + e)
  }

}
</script>

<style scoped>
#UserLoginPage {
  max-width: 400px;
  margin: 0 auto;
}
.title {
  text-align: center;
  margin-bottom: 16px;
}
.desc {
  text-align: center;
  color: #bbb;
  margin-bottom: 16px;
}
.tips {
  color: #bbb;
  text-align: right;
  font-size: 13px;
  margin-bottom: 16px;
}
</style>
