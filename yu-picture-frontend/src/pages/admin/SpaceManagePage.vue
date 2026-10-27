<template>
  <div id="spaceManagePage">
    <a-flex justify="space-between">
      <h2>空间管理</h2>
      <a-space>
        <a-button type="primary" href="/add_space" target="_blank">+ 创建空间</a-button>
        <!-- 分析入口：带查询范围参数跳到分析页，两种范围都需要管理员权限（后端已校验） -->
        <a-button type="primary" ghost href="/space_analyze?queryPublic=1" target="_blank">
          分析公共图库
        </a-button>
        <a-button type="primary" ghost href="/space_analyze?queryAll=1" target="_blank">
          分析全空间
        </a-button>
      </a-space>
    </a-flex>
    <div style="margin-bottom: 16px" />
    <!-- 搜索表单 -->
    <a-form layout="inline" :model="searchParams" @finish="doSearch">
      <a-form-item label="空间名称" name="spaceName">
        <a-input v-model:value="searchParams.spaceName" placeholder="请输入空间名称" allow-clear />
      </a-form-item>
      <a-form-item label="空间级别" name="spaceLevel">
        <a-select
          v-model:value="searchParams.spaceLevel"
          style="min-width: 180px"
          placeholder="请选择空间级别"
          :options="SPACE_LEVEL_OPTIONS"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="空间类别" name="spaceType">
        <a-select
          v-model:value="searchParams.spaceType"
          style="min-width: 180px"
          placeholder="请选择空间类别"
          :options="SPACE_TYPE_OPTIONS"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="用户 id" name="userId">
        <a-input v-model:value="searchParams.userId" placeholder="请输入用户 id" allow-clear />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit">搜索</a-button>
      </a-form-item>
    </a-form>
    <a-divider />
    <!-- 表格 -->
    <a-table
      row-key="id"
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      :loading="loading"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'spaceLevel'">
          <a-tag :color="getSpaceLevelColor(record.spaceLevel)">
            {{ getSpaceLevelIcon(record.spaceLevel) }}
            {{ SPACE_LEVEL_MAP[record.spaceLevel] ?? '未知' }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'spaceType'">
          <!-- 老数据的 spaceType 是数据库默认值 0，兜底按私有空间展示 -->
          <a-tag>{{ SPACE_TYPE_MAP[record.spaceType] ?? '私有空间' }}</a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'spaceUseInfo'">
          <div>大小：{{ formatSize(record.totalSize) }} / {{ formatSize(record.maxSize) }}</div>
          <div>数量：{{ record.totalCount }} / {{ record.maxCount }}</div>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.dataIndex === 'editTime'">
          {{ formatTime(record.editTime) }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space wrap>
            <a-button type="link" @click="doEdit(record.id)">编辑</a-button>
            <!-- 直接分析某一个空间：record.id 是雪花 id，模板里已经是字符串形态，拼 URL 不丢精度 -->
            <a-button type="link" :href="`/space_analyze?spaceId=${record.id}`" target="_blank">
              分析
            </a-button>
            <a-button type="link" danger @click="doDelete(record)">删除</a-button>
          </a-space>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { deleteSpaceUsingPost, listSpaceByPageUsingPost } from '@/api/spaceController.ts'
import {
  SPACE_LEVEL_MAP,
  SPACE_LEVEL_OPTIONS,
  SPACE_TYPE_MAP,
  SPACE_TYPE_OPTIONS,
  getSpaceLevelColor,
  getSpaceLevelIcon,
} from '@/constants/space.ts'
import { formatSize } from '@/utils'

const router = useRouter()

/** 表格列配置 */
const columns = [
  {
    title: 'id',
    dataIndex: 'id',
    width: 80,
  },
  {
    title: '空间名称',
    dataIndex: 'spaceName',
  },
  {
    title: '空间级别',
    dataIndex: 'spaceLevel',
  },
  {
    title: '空间类别',
    dataIndex: 'spaceType',
  },
  {
    title: '使用情况',
    dataIndex: 'spaceUseInfo',
  },
  {
    title: '用户 id',
    dataIndex: 'userId',
    width: 80,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '编辑时间',
    dataIndex: 'editTime',
  },
  {
    title: '操作',
    key: 'action',
  },
]

// 数据
const dataList = ref<API.Space[]>([])
const total = ref(0)
const loading = ref(true)

// 搜索条件
const searchParams = reactive<API.SpaceQueryRequest>({
  current: 1,
  pageSize: 10,
  sortField: 'createTime',
  sortOrder: 'descend',
})

// 分页配置
const pagination = reactive({
  current: searchParams.current,
  pageSize: searchParams.pageSize,
  total: total.value,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`,
})

/**
 * 获取数据
 *
 * 注意调用的是仅管理员可用的 listSpaceByPage，不是给用户用的 listSpaceVoByPage：
 * 管理端要看的是全部空间（含各用户的私有空间），包装类接口反而多查一遍用户信息
 */
const fetchData = async () => {
  loading.value = true
  const res = await listSpaceByPageUsingPost({ ...searchParams })
  if (res.data.code === 0 && res.data.data) {
    dataList.value = res.data.data.records ?? []
    total.value = res.data.data.total ?? 0
    pagination.total = total.value
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
}

// 表格变化处理
// 参数写成可选属性：a-table 的 change 回调传的是 TablePaginationConfig，
// 它里面 current / pageSize 都是可选的，写成必填会和它的类型对不上
const doTableChange = (page: { current?: number; pageSize?: number }) => {
  searchParams.current = page.current ?? 1
  searchParams.pageSize = page.pageSize ?? 10
  pagination.current = searchParams.current
  pagination.pageSize = searchParams.pageSize
  fetchData()
}

// 搜索
const doSearch = () => {
  // 重置页码
  searchParams.current = 1
  pagination.current = 1
  fetchData()
}

// 编辑：跳转创建空间页，带上 id 就是编辑（管理员可改级别和限额）
const doEdit = (id: API.Space['id']) => {
  router.push({
    path: '/add_space',
    query: { id },
  })
}

/**
 * 删除空间
 *
 * 删除空间会连带删掉空间里的所有图片（后端 deleteSpace 里做的级联删除），
 * 所以确认文案必须把这件事说清楚：不然管理员以为只是删个壳子，结果把人家一个相册清空了
 *
 * onOk 返回 Promise，antd 会在接口返回前把确定按钮置为 loading，连点也不会重复提交
 * @param space 要删除的空间（需要 totalCount 来提示里面有多少张图）
 */
const doDelete = (space: API.Space) => {
  const id = space?.id
  if (!id) {
    return
  }
  const pictureCount = space.totalCount ?? 0
  Modal.confirm({
    title: '确认删除这个空间吗？',
    content:
      pictureCount > 0
        ? `空间内的 ${pictureCount} 张图片会一并删除（含对象存储上的文件），删除后不可恢复。`
        : '删除后不可恢复，空间信息将从列表中移除。',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      // id 直接用，不做 Number() 转换：雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度
      const res = await deleteSpaceUsingPost({ id })
      if (res.data.code === 0) {
        message.success('删除成功')
        fetchData()
      } else {
        message.error('删除失败，' + res.data.message)
        // 失败时把 Promise 抛出去，让确认框保持打开，管理员能重试
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
</script>

<style scoped>
#spaceManagePage {
  margin-bottom: 16px;
}
</style>
