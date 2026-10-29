<template>
  <div id="UserCenterPage">
    <a-row :gutter="24">
      <a-col :xs="24" :md="12">
        <a-card title="个人信息">
          <div class="avatar-row">
            <a-upload
              :show-upload-list="false"
              :custom-request="handleAvatarUpload"
              :before-upload="beforeAvatarUpload"
              accept="image/png,image/jpeg,image/gif,image/webp"
            >
              <a-tooltip title="点击更换头像">
                <a-spin :spinning="uploadingAvatar">
                  <a-avatar :size="72" :src="formState.userAvatar || undefined" :style="avatarStyle">
                    {{ avatarText }}
                  </a-avatar>
                </a-spin>
              </a-tooltip>
            </a-upload>
            <div class="avatar-tip">
              <a-tag v-if="isAdmin" color="gold">管理员</a-tag>
              <a-tag v-else color="blue">普通用户</a-tag>
              <div class="avatar-tip-text">点击头像上传新头像，不超过 2M</div>
            </div>
          </div>
          <a-form layout="vertical" :model="formState" @finish="saveProfile">
            <a-form-item label="账号">
              <a-input :value="loginUser.userAccount" disabled />
            </a-form-item>
            <a-form-item label="手机号">
              <a-input :value="loginUser.phone || '未绑定'" disabled />
            </a-form-item>
            <a-form-item label="用户 ID" extra="添加团队空间成员时需要填写这个 ID">
              <a-input :value="loginUser.id" readonly>
                <template #addonAfter>
                  <a @click="copyUserId">复制</a>
                </template>
              </a-input>
            </a-form-item>
            <a-form-item
              label="昵称"
              name="userName"
              :rules="[{ required: true, message: '请输入昵称' }]"
            >
              <a-input v-model:value="formState.userName" :maxlength="50" placeholder="请输入昵称" />
            </a-form-item>
            <a-form-item label="简介" name="userProfile">
              <a-textarea
                v-model:value="formState.userProfile"
                :rows="3"
                :maxlength="512"
                show-count
                placeholder="介绍一下自己吧"
              />
            </a-form-item>
            <a-button type="primary" html-type="submit" :loading="savingProfile">保存资料</a-button>
          </a-form>
        </a-card>
      </a-col>

      <a-col :xs="24" :md="12">
        <a-card :title="hasPassword ? '修改密码' : '设置密码'">
          <a-alert
            v-if="!hasPassword"
            type="info"
            show-icon
            style="margin-bottom: 16px"
            message="当前账号还没有设置密码，设置后也可以使用账号密码登录"
          />
          <a-form layout="vertical" :model="passwordForm" @finish="savePassword">
            <a-form-item
              v-if="hasPassword"
              label="原密码"
              name="oldPassword"
              :rules="[{ required: true, message: '请输入原密码' }]"
            >
              <a-input-password v-model:value="passwordForm.oldPassword" placeholder="请输入原密码" />
            </a-form-item>
            <a-form-item
              label="新密码"
              name="newPassword"
              :rules="[
                { required: true, message: '请输入新密码' },
                { min: 8, max: 32, message: '密码长度应为 8-32 位' },
              ]"
            >
              <a-input-password v-model:value="passwordForm.newPassword" placeholder="8-32 位" />
            </a-form-item>
            <a-form-item
              label="确认新密码"
              name="checkPassword"
              :rules="[{ required: true, message: '请再次输入新密码' }, checkPasswordRule]"
            >
              <a-input-password
                v-model:value="passwordForm.checkPassword"
                placeholder="请再次输入新密码"
              />
            </a-form-item>
            <a-button type="primary" html-type="submit" :loading="savingPassword">
              {{ hasPassword ? '修改密码' : '设置密码' }}
            </a-button>
          </a-form>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { UploadProps } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import router from '@/router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import {
  updatePasswordUsingPost,
  updateProfileUsingPost,
  uploadAvatarUsingPost,
} from '@/api/userController.ts'

const loginUserStore = useLoginUserStore()
const loginUser = computed(() => loginUserStore.loginUser)
const isAdmin = computed(() => loginUser.value.userRole === 'admin')
const hasPassword = computed(() => !!loginUser.value.hasPassword)

const formState = reactive<API.UserProfileUpdateRequest>({
  userName: '',
  userAvatar: '',
  userProfile: '',
})

const passwordForm = reactive<API.UserPasswordUpdateRequest>({
  oldPassword: '',
  newPassword: '',
  checkPassword: '',
})

const savingProfile = ref(false)
const savingPassword = ref(false)
const uploadingAvatar = ref(false)

// customRequest / beforeUpload 的入参类型从签名推导，避免写 any
type UploadRequestOption = Parameters<NonNullable<UploadProps['customRequest']>>[0]
type BeforeUploadFile = Parameters<NonNullable<UploadProps['beforeUpload']>>[0]

const avatarText = computed(() => (loginUser.value.userName || 'U').slice(0, 1).toUpperCase())
const avatarStyle = computed(() => ({
  backgroundColor: '#1677ff',
  color: '#fff',
  verticalAlign: 'middle',
}))

const checkPasswordRule = {
  validator: (_rule: unknown, value: string) =>
    value === passwordForm.newPassword ? Promise.resolve() : Promise.reject('两次输入的密码不一致'),
}

// 登录用户信息变化时同步到表单
watch(
  () => loginUserStore.loginUser,
  (user) => {
    formState.userName = user.userName || ''
    formState.userAvatar = user.userAvatar || ''
    formState.userProfile = user.userProfile || ''
  },
  { immediate: true, deep: true },
)

onMounted(async () => {
  if (!loginUserStore.loginUser.id) {
    await loginUserStore.fetchLoginUser()
  }
  if (!loginUserStore.loginUser.id) {
    await router.replace({ path: '/user/login', query: { redirect: '/user/center' } })
  }
})

/**
 * 复制用户 ID（添加空间成员时用）
 */
const copyUserId = async () => {
  const userId = loginUser.value.id
  if (!userId) {
    return
  }
  try {
    await navigator.clipboard.writeText(String(userId))
    message.success('用户 ID 已复制')
  } catch {
    message.warning('复制失败，请手动选择复制')
  }
}

/**
 * 保存个人资料
 */
const saveProfile = async () => {
  savingProfile.value = true
  try {
    const res = await updateProfileUsingPost({ ...formState })
    if (res.data.code === 0) {
      await loginUserStore.fetchLoginUser()
      message.success('资料已保存')
    } else {
      message.error(res.data.message || '保存失败')
    }
  } catch (e) {
    message.error('保存失败' + e)
  } finally {
    savingProfile.value = false
  }
}

/**
 * 上传头像前的格式和大小校验
 */
const beforeAvatarUpload = (file: BeforeUploadFile) => {
  const isAllowed = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'].includes(file.type)
  if (!isAllowed) {
    message.error('仅支持 jpg、png、gif、webp 格式的图片')
  }
  const isLt2M = file.size / 1024 / 1024 < 2
  if (!isLt2M) {
    message.error('头像不能超过 2M')
  }
  return isAllowed && isLt2M
}

/**
 * 上传头像，后端会自动保存到当前用户
 */
const handleAvatarUpload = async ({ file }: UploadRequestOption) => {
  if (!(file instanceof File)) {
    return
  }
  uploadingAvatar.value = true
  try {
    const res = await uploadAvatarUsingPost({}, file)
    if (res.data.code === 0 && res.data.data) {
      formState.userAvatar = res.data.data
      await loginUserStore.fetchLoginUser()
      message.success('头像已更新')
    } else {
      message.error(res.data.message || '头像上传失败')
    }
  } catch (e) {
    message.error('头像上传失败' + e)
  } finally {
    uploadingAvatar.value = false
  }
}

/**
 * 设置 / 修改密码
 */
const savePassword = async () => {
  savingPassword.value = true
  try {
    const res = await updatePasswordUsingPost({
      oldPassword: hasPassword.value ? passwordForm.oldPassword : undefined,
      newPassword: passwordForm.newPassword,
      checkPassword: passwordForm.checkPassword,
    })
    if (res.data.code === 0) {
      message.success(hasPassword.value ? '密码修改成功' : '密码设置成功')
      passwordForm.oldPassword = ''
      passwordForm.newPassword = ''
      passwordForm.checkPassword = ''
      await loginUserStore.fetchLoginUser()
    } else {
      message.error(res.data.message || '操作失败')
    }
  } catch (e) {
    message.error('操作失败' + e)
  } finally {
    savingPassword.value = false
  }
}
</script>

<style scoped>
#UserCenterPage {
  max-width: 1000px;
  margin: 0 auto;
}
.avatar-row {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}
.avatar-tip {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.avatar-tip-text {
  color: #999;
  font-size: 12px;
}
</style>
