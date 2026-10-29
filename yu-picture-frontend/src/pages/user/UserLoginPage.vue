<template>
  <div id="UserLoginPage">
    <h2 class="title">图协云 - 用户登录</h2>
    <div class="desc">AI协同图片管理平台</div>
    <a-tabs v-model:activeKey="activeTab" centered>
      <a-tab-pane key="password" tab="密码登录">
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
          <a-form-item>
            <a-button type="primary" size="large" html-type="submit" style="width: 100%">登录</a-button>
          </a-form-item>
          <div class="forgot-row">
            <a @click="openResetModal">忘记密码 / 首次设置密码</a>
          </div>
        </a-form>
      </a-tab-pane>

      <a-tab-pane key="sms" tab="验证码登录">
        <a-form :model="smsFormState" name="sms" autocomplete="off" @finish="handleSmsSubmit">
          <a-form-item name="phone" :rules="phoneRules">
            <a-input
              size="large"
              v-model:value="smsFormState.phone"
              placeholder="请输入手机号"
              :maxlength="11"
            />
          </a-form-item>
          <a-form-item name="code" :rules="[{ required: true, message: '请输入验证码' }]">
            <div class="code-row">
              <a-input
                size="large"
                v-model:value="smsFormState.code"
                placeholder="请输入验证码"
                :maxlength="6"
              />
              <a-button size="large" :disabled="countdown > 0" :loading="sending" @click="sendCode">
                {{ countdown > 0 ? `${countdown}s 后重发` : '发送验证码' }}
              </a-button>
            </div>
          </a-form-item>
          <div class="tips">未注册的手机号验证通过后会</div>
          <div class="tips">自动创建一个账号，无需设置密码</div>
          <a-form-item>
            <a-button
              type="primary"
              size="large"
              html-type="submit"
              style="width: 100%"
              :loading="loggingIn"
            >
              登录 / 注册
            </a-button>
          </a-form-item>
        </a-form>
      </a-tab-pane>
    </a-tabs>

    <a-modal
      v-model:open="resetVisible"
      title="通过手机验证码设置新密码"
      ok-text="确认重置"
      cancel-text="取消"
      :confirm-loading="resetting"
      @ok="handleResetSubmit"
    >
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px"
        message="验证码注册的账号可以在这里设置密码；忘记密码也可以用它重置"
      />
      <a-form layout="vertical">
        <a-form-item label="手机号" required>
          <a-input v-model:value="resetForm.phone" :maxlength="11" placeholder="请输入注册手机号" />
        </a-form-item>
        <a-form-item label="验证码" required>
          <div class="code-row">
            <a-input v-model:value="resetForm.code" :maxlength="6" placeholder="请输入验证码" />
            <a-button :disabled="resetCountdown > 0" :loading="resetSending" @click="sendResetCode">
              {{ resetCountdown > 0 ? `${resetCountdown}s 后重发` : '发送验证码' }}
            </a-button>
          </div>
        </a-form-item>
        <a-form-item label="新密码" required>
          <a-input-password v-model:value="resetForm.newPassword" placeholder="8-32 位" />
        </a-form-item>
        <a-form-item label="确认新密码" required>
          <a-input-password v-model:value="resetForm.checkPassword" placeholder="请再次输入新密码" />
        </a-form-item>
      </a-form>
    </a-modal>

    <div class="tips">
      没有账号？
      <RouterLink to="/user/register">去注册</RouterLink>
    </div>
  </div>
</template>
<script lang="ts" setup>
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  resetPasswordUsingPost,
  sendSmsCodeUsingPost,
  userLoginUsingPost,
  userSmsLoginUsingPost,
} from '@/api/userController.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { message } from 'ant-design-vue'
import router from '@/router'

const PHONE_REGEX = /^1[3-9]\d{9}$/

//用于接收表单输入的值
const formState = reactive<API.UserLoginRequest>({
  userAccount: '',
  userPassword: '',
})

//验证码登录表单
const smsFormState = reactive<API.UserSmsLoginRequest>({
  phone: '',
  code: '',
})

//重置/首次设置密码表单
const resetForm = reactive<API.UserPasswordResetRequest>({
  phone: '',
  code: '',
  newPassword: '',
  checkPassword: '',
})

const activeTab = ref('password')
const sending = ref(false)
const loggingIn = ref(false)
const countdown = ref(0)
let countdownTimer: number | undefined

const resetVisible = ref(false)
const resetSending = ref(false)
const resetting = ref(false)
const resetCountdown = ref(0)
let resetTimer: number | undefined

const loginUserStore = useLoginUserStore()
const route = useRoute()

const phoneRules = [
  { required: true, message: '请输入手机号' },
  {
    validator: (_rule: unknown, value: string) =>
      PHONE_REGEX.test(value) ? Promise.resolve() : Promise.reject('手机号格式不正确'),
  },
]

const redirectPath = computed(() =>
  typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
    ? route.query.redirect
    : '/',
)

/**
 * 登录成功后的公共处理
 */
const afterLoginSuccess = async () => {
  await loginUserStore.fetchLoginUser()
  message.success('登录成功')
  router.replace(redirectPath.value)
}

/**
 * 密码登录
 */
const handleSubmit = async (values: API.UserLoginRequest) => {
  try {
    const res = await userLoginUsingPost(values)
    if (res.data.code === 0 && res.data.data) {
      await afterLoginSuccess()
    } else {
      message.error('登录失败：' + res.data.message)
    }
  } catch (e) {
    message.error('登录失败' + e)
  }
}

/**
 * 发送短信验证码
 */
const sendCode = async () => {
  if (!PHONE_REGEX.test(smsFormState.phone || '')) {
    message.warning('请输入正确的手机号')
    return
  }
  sending.value = true
  try {
    const res = await sendSmsCodeUsingPost({ phone: smsFormState.phone })
    if (res.data.code === 0) {
      message.success('验证码已发送')
      countdown.value = 60
      countdownTimer = window.setInterval(() => {
        countdown.value--
        if (countdown.value <= 0 && countdownTimer) {
          window.clearInterval(countdownTimer)
          countdownTimer = undefined
        }
      }, 1000)
    } else {
      message.error(res.data.message || '验证码发送失败')
    }
  } catch (e) {
    message.error('验证码发送失败' + e)
  } finally {
    sending.value = false
  }
}

/**
 * 验证码登录（未注册手机号自动注册）
 */
const handleSmsSubmit = async () => {
  loggingIn.value = true
  try {
    const res = await userSmsLoginUsingPost(smsFormState)
    if (res.data.code === 0 && res.data.data) {
      await afterLoginSuccess()
    } else {
      message.error('登录失败：' + res.data.message)
    }
  } catch (e) {
    message.error('登录失败' + e)
  } finally {
    loggingIn.value = false
  }
}

/**
 * 打开重置密码弹窗，手机号默认带上验证码登录里填的
 */
const openResetModal = () => {
  resetForm.phone = resetForm.phone || smsFormState.phone || ''
  resetVisible.value = true
}

/**
 * 重置密码时发送验证码
 */
const sendResetCode = async () => {
  if (!PHONE_REGEX.test(resetForm.phone || '')) {
    message.warning('请输入正确的手机号')
    return
  }
  resetSending.value = true
  try {
    const res = await sendSmsCodeUsingPost({ phone: resetForm.phone })
    if (res.data.code === 0) {
      message.success('验证码已发送')
      resetCountdown.value = 60
      resetTimer = window.setInterval(() => {
        resetCountdown.value--
        if (resetCountdown.value <= 0 && resetTimer) {
          window.clearInterval(resetTimer)
          resetTimer = undefined
        }
      }, 1000)
    } else {
      message.error(res.data.message || '验证码发送失败')
    }
  } catch (e) {
    message.error('验证码发送失败' + e)
  } finally {
    resetSending.value = false
  }
}

/**
 * 通过验证码设置新密码
 */
const handleResetSubmit = async () => {
  if (!PHONE_REGEX.test(resetForm.phone || '')) {
    message.warning('请输入正确的手机号')
    return
  }
  if (!resetForm.code) {
    message.warning('请输入验证码')
    return
  }
  const newPassword = resetForm.newPassword || ''
  if (newPassword.length < 8 || newPassword.length > 32) {
    message.warning('密码长度应为 8-32 位')
    return
  }
  if (newPassword !== resetForm.checkPassword) {
    message.warning('两次输入的密码不一致')
    return
  }
  resetting.value = true
  try {
    const res = await resetPasswordUsingPost({ ...resetForm })
    if (res.data.code === 0) {
      message.success('密码设置成功，请使用新密码登录')
      formState.userAccount = resetForm.phone || ''
      resetForm.code = ''
      resetForm.newPassword = ''
      resetForm.checkPassword = ''
      resetVisible.value = false
    } else {
      message.error(res.data.message || '设置失败')
    }
  } catch (e) {
    message.error('设置失败' + e)
  } finally {
    resetting.value = false
  }
}

onUnmounted(() => {
  if (countdownTimer) {
    window.clearInterval(countdownTimer)
  }
  if (resetTimer) {
    window.clearInterval(resetTimer)
  }
})
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
  margin-bottom: 8px;
}
.code-row {
  display: flex;
  gap: 12px;
}
.forgot-row {
  text-align: right;
  font-size: 13px;
}
</style>
