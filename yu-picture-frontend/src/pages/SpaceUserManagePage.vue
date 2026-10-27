<template>
  <div id="spaceUserManagePage">
    <a-flex justify="space-between" align="center" wrap :gap="16">
      <h2 style="margin: 0">空间成员管理</h2>
      <!-- 成员管理页通常是新标签页打开的，给一个回空间详情的入口，省得用户自己找 -->
      <a-button type="primary" ghost :href="`/space/${id}`" target="_blank">返回空间</a-button>
    </a-flex>
    <div style="margin-bottom: 16px" />

    <!-- 没有 spaceUser:manage 权限（例如浏览者 / 编辑者直接输地址进来）时，整页换成结果提示 -->
    <a-result v-if="errorMsg" status="403" :title="errorMsg" sub-title="仅空间管理员可以管理成员" />
    <template v-else>
      <!-- 添加成员表单：后端默认角色是 viewer（浏览者），加进来之后再在下表里改角色 -->
      <a-form layout="inline" :model="formData" @finish="handleSubmit">
        <a-form-item label="用户 id" name="userId">
          <a-input v-model:value="formData.userId" placeholder="请输入用户 id" allow-clear />
        </a-form-item>
        <a-form-item>
          <a-button type="primary" html-type="submit" :loading="addLoading">添加用户</a-button>
        </a-form-item>
      </a-form>
      <div style="margin-bottom: 16px" />

      <!-- 成员列表：人数不会太多，后端接口也不分页，直接一次全量展示 -->
      <a-table
        row-key="id"
        :columns="columns"
        :data-source="dataList"
        :loading="loading"
        :pagination="false"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'userInfo'">
            <a-space>
              <a-avatar :src="record.user?.userAvatar || undefined">
                {{ (record.user?.userName ?? '?').slice(0, 1) }}
              </a-avatar>
              <span>{{ record.user?.userName ?? record.userId }}</span>
            </a-space>
          </template>
          <template v-else-if="column.dataIndex === 'spaceRole'">
            <!-- 角色下拉框直接绑在行数据上，改完立刻调接口；失败时重新拉列表还原 -->
            <a-select
              v-model:value="record.spaceRole"
              :options="SPACE_ROLE_OPTIONS"
              style="min-width: 120px"
              @change="(value: string) => editSpaceRole(value, record)"
            />
          </template>
          <template v-else-if="column.dataIndex === 'createTime'">
            {{ formatTime(record.createTime) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <a-space wrap>
              <a-button type="link" danger @click="doDelete(record)">删除</a-button>
            </a-space>
          </template>
        </template>
      </a-table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  addSpaceUserUsingPost,
  deleteSpaceUserUsingPost,
  editSpaceUserUsingPost,
  listSpaceUserUsingPost,
} from '@/api/spaceUserController.ts'
import { SPACE_ROLE_OPTIONS } from '@/constants/space.ts'
import { toApiId } from '@/utils'

interface Props {
  /** 路由参数 /spaceUserManage/:id */
  id: string | number
}

const props = defineProps<Props>()

/**
 * 空间 id 的接口形态
 *
 * 雪花 id 超过 Number.MAX_SAFE_INTEGER，不能做 Number() 转换；
 * toApiId 只是骗过类型（后端生成的 typings 是 number），运行时传的仍是字符串
 */
const spaceIdParam = () => toApiId(props.id)

/** 表格列 */
const columns = [
  {
    title: '用户',
    dataIndex: 'userInfo',
  },
  {
    title: '角色',
    dataIndex: 'spaceRole',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '操作',
    key: 'action',
  },
]

const dataList = ref<API.SpaceUserVO[]>([])
const loading = ref(false)
/** 加载失败的提示（主要是「没有成员管理权限」），有值时整页换成结果页 */
const errorMsg = ref('')

/** 添加成员表单 */
const formData = reactive<API.SpaceUserAddRequest>({})
const addLoading = ref(false)

/** 获取成员列表 */
const fetchData = async () => {
  if (!props.id) {
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    const res = await listSpaceUserUsingPost({ spaceId: spaceIdParam() })
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data
    } else {
      // 浏览者 / 编辑者直接输地址进来会走到这里（后端按 spaceUser:manage 校验）
      errorMsg.value = '获取成员列表失败，' + res.data.message
      dataList.value = []
    }
  } catch (error) {
    errorMsg.value = '获取成员列表失败，' + (error as Error).message
  } finally {
    loading.value = false
  }
}

/**
 * 添加成员
 *
 * 先校验用户 id 是否填了，避免发一个必然 400 的请求；
 * 成功后清空输入框并刷新列表 —— 不清空的话用户容易误以为没提交成功再点一次
 */
const handleSubmit = async () => {
  if (!formData.userId) {
    message.warning('请输入用户 id')
    return
  }
  addLoading.value = true
  try {
    const res = await addSpaceUserUsingPost({
      spaceId: spaceIdParam(),
      ...formData,
    })
    if (res.data.code === 0) {
      message.success('添加成功')
      formData.userId = undefined
      fetchData()
    } else {
      message.error('添加失败，' + res.data.message)
    }
  } catch (error) {
    message.error('添加失败，' + (error as Error).message)
  } finally {
    addLoading.value = false
  }
}

/**
 * 修改成员角色
 *
 * 下拉框先把本地值改了，接口失败时重新拉一次列表，把显示还原成服务端的角色
 */
const editSpaceRole = async (value: string, record: API.SpaceUserVO) => {
  if (!record.id) {
    return
  }
  try {
    const res = await editSpaceUserUsingPost({
      id: record.id,
      spaceRole: value,
    })
    if (res.data.code === 0) {
      message.success('修改成功')
    } else {
      message.error('修改失败，' + res.data.message)
      fetchData()
    }
  } catch (error) {
    message.error('修改失败，' + (error as Error).message)
    fetchData()
  }
}

/**
 * 删除成员
 *
 * 移除不可恢复（被移除的人要重新被邀请才能加入），先弹确认框
 */
const doDelete = (record: API.SpaceUserVO) => {
  if (!record.id) {
    return
  }
  const userName = record.user?.userName ?? record.userId
  Modal.confirm({
    title: `确认移除成员「${userName}」吗？`,
    content: '移除后该用户将无法再查看和操作这个团队空间，需要重新添加才能加入。',
    okText: '确认移除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      const res = await deleteSpaceUserUsingPost({ id: toApiId(record.id) })
      if (res.data.code === 0) {
        message.success('删除成功')
        fetchData()
      } else {
        message.error('删除失败，' + res.data.message)
        // 失败时把 Promise 抛出去，让确认框保持打开，用户能重试
        throw new Error(res.data.message)
      }
    },
  })
}

/**
 * 格式化时间
 * @param time
 */
const formatTime = (time?: string) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

onMounted(() => {
  fetchData()
})

/** 同一页面里切换空间 id 时重新加载（例如以后从空间详情内部跳转过来） */
watch(
  () => props.id,
  () => {
    fetchData()
  },
)
</script>

<style scoped>
#spaceUserManagePage {
  margin-bottom: 16px;
}
</style>
