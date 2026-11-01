<template>
  <div id="userRegisterPage">
    <h2 class="title">图片分享-浮光图记 - 用户注册</h2>
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

      <a-form-item
        name="checkPassword"
        :rules="[
          { required: true, message: '请输入确认密码' },
          { min: 8, message: '确认密码长度不能少于8位' },
        ]"
      >
        <a-input-password size="large" v-model:value="formState.checkPassword" placeholder="请输入确认密码" />
      </a-form-item>
      <div class="tips">
        已有账号？
        <RouterLink to="/user/login">去登录</RouterLink>
      </div>
      <a-form-item>
        <a-button type="primary" size="large" html-type="submit" style="width: 100%">注册</a-button>
      </a-form-item>
    </a-form>
  </div>
</template>
<script lang="ts" setup>
import { reactive } from 'vue'
import { useRoute } from 'vue-router'
import { userRegistreUsingPost } from '@/api/userController.ts'
import { message } from 'ant-design-vue'
import router from '@/router'

//用于接收表单输入的值
const formState = reactive<API.UserRegisterRequest>({
  userAccount: '',
  userPassword: '',
  checkPassword: '',
})

const route = useRoute()

/**
 * 表单提交
 * @param values
 */
const handleSubmit = async (values: API.UserRegisterRequest) => {
  try {
    if (values.userPassword !== values.checkPassword) {
      message.error('两次输入的密码不一致')
      return;
    }
    const res = await userRegistreUsingPost(values)
    //注册成功,跳转到登录页
    if (res.data.code === 0 && res.data.data) {
      message.success('注册成功')
      // 取出被拦截页面留下的 redirect 参数，注册成功后跳回原页面（只允许站内路径，防止开放重定向）
      const redirect =
        typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/user/login')
          ? route.query.redirect
          : '/user/login'
      router.replace(redirect)
    } else {
      message.error('登陆失败' + res.data.message)
    }
  } catch (e) {
    message.error('注册失败' + e)
  }
}
</script>

<style scoped>
#userRegisterPage {
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
