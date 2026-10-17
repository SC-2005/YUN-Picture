<template>
  <div id="addSpacePage">
    <h2 style="margin-bottom: 16px">{{ isEdit ? '修改空间' : '创建空间' }}</h2>
    <!-- 空间信息表单 -->
    <a-form name="spaceForm" layout="vertical" :model="spaceForm" @finish="handleSubmit">
      <a-form-item name="spaceName" label="空间名称">
        <a-input v-model:value="spaceForm.spaceName" placeholder="请输入空间名称" allow-clear />
      </a-form-item>
      <a-form-item name="spaceLevel" label="空间级别">
        <!-- 普通用户编辑自己的空间时不能改级别：后端的 editSpace 接口只接受空间名称 -->
        <a-select
          v-model:value="spaceForm.spaceLevel"
          style="min-width: 180px"
          placeholder="请选择空间级别"
          :options="SPACE_LEVEL_OPTIONS"
          :disabled="levelDisabled"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" :loading="loading" style="width: 100%">
          提交
        </a-button>
      </a-form-item>
    </a-form>
    <!-- 空间级别介绍 -->
    <a-card title="空间级别介绍">
      <a-typography-paragraph>
        * 目前仅支持开通普通版，如需升级空间，请联系程序员常念（QQ：3445166212）。
      </a-typography-paragraph>
      <a-typography-paragraph v-for="spaceLevel in spaceLevelList" :key="spaceLevel.value">
        {{ getSpaceLevelIcon(spaceLevel.value) }}
        {{ spaceLevel.text }}：大小 {{ formatSize(spaceLevel.maxSize) }}，数量
        {{ spaceLevel.maxCount }}
      </a-typography-paragraph>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import {
  addSpaceUsingPost,
  editSpaceUsingPost,
  getSpaceVoByIdUsingGet,
  listSpaceLevelUsingGet,
  updateSpaceUsingPost,
} from '@/api/spaceController.ts'
import { SPACE_LEVEL_ENUM, SPACE_LEVEL_OPTIONS, getSpaceLevelIcon } from '@/constants/space.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { formatSize, toApiId } from '@/utils'

const route = useRoute()
const router = useRouter()
const loginUserStore = useLoginUserStore()

/** 老数据：带 ?id= 进来说明是编辑已有空间 */
const oldSpace = ref<API.SpaceVO>()

// 空间级别默认给普通版：后端 addSpace 对 null 也是兜底成普通版，
// 这里显式给上，用户看到的下拉框和实际创建的级别才一致
const spaceForm = reactive<API.SpaceAddRequest>({
  spaceName: '',
  spaceLevel: SPACE_LEVEL_ENUM.COMMON,
})
const loading = ref(false)

const spaceLevelList = ref<API.SpaceLevel[]>([])

const isEdit = computed(() => Boolean(route.query?.id))
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')
/** 编辑态下只有管理员能改级别：改级别等于改限额，那是管理员专属的 updateSpace 的能力 */
const levelDisabled = computed(() => isEdit.value && !isAdmin.value)

/**
 * 获取空间级别列表
 *
 * 用于页面下方的介绍卡片。级别对应的限额由后端 SpaceLevelEnum 统一下发，
 * 前端不再写死一份，避免两边改不同步
 */
const fetchSpaceLevelList = async () => {
  const res = await listSpaceLevelUsingGet()
  if (res.data.code === 0 && res.data.data) {
    spaceLevelList.value = res.data.data
  } else {
    message.error('获取空间级别失败，' + res.data.message)
  }
}

/**
 * 获取老数据并填充表单
 */
const getOldSpace = async () => {
  const id = route.query?.id
  if (!id) {
    return
  }
  // 不能用 Number() 转换：雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度（见 toApiId 注释）
  const res = await getSpaceVoByIdUsingGet({ id: toApiId(id) })
  if (res.data.code !== 0 || !res.data.data) {
    message.error('获取空间信息失败，' + res.data.message)
    return
  }
  const data = res.data.data
  // 既不是空间创建人、也不是管理员，就没资格改这个空间；
  // 后端也会拦（updateSpace 要管理员、editSpace 要创建人），这里提前给个明确反馈而不是等他提交完报错
  const isOwner = String(data.userId) === String(loginUserStore.loginUser.id)
  if (!isOwner && !isAdmin.value) {
    message.error('没有权限编辑该空间')
    router.replace('/')
    return
  }
  oldSpace.value = data
  spaceForm.spaceName = data.spaceName
  spaceForm.spaceLevel = data.spaceLevel
}

/**
 * 提交表单
 */
const handleSubmit = async () => {
  const spaceId = oldSpace.value?.id
  loading.value = true
  try {
    let path: string
    if (spaceId) {
      // 更新：管理员走 update 接口（可改级别），空间创建人走 edit 接口（只能改名）。
      // 两个接口返回的都是 Boolean，不能拿返回值拼路径，直接用当前空间 id
      const res = isAdmin.value
        ? await updateSpaceUsingPost({ id: toApiId(spaceId), ...spaceForm })
        : await editSpaceUsingPost({ id: toApiId(spaceId), spaceName: spaceForm.spaceName })
      if (res.data.code !== 0) {
        message.error('操作失败，' + res.data.message)
        return
      }
      path = `/space/${spaceId}`
    } else {
      // 创建：返回的是新空间的 id（雪花 id 在 JSON 里是字符串），直接拼进路径即可
      const res = await addSpaceUsingPost({ ...spaceForm })
      if (res.data.code !== 0 || res.data.data == null) {
        message.error('操作失败，' + res.data.message)
        return
      }
      path = `/space/${res.data.data}`
    }
    message.success('操作成功')
    router.push({ path })
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  fetchSpaceLevelList()
  getOldSpace()
})
</script>

<style scoped>
#addSpacePage {
  max-width: 720px;
  margin: 0 auto;
}
</style>
