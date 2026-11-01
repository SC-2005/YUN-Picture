<template>
  <div id="pictureManagePage">
    <a-flex justify="space-between" wrap :gap="16">
      <h2>图片管理</h2>
      <a-space wrap>
        <a-button type="primary" href="/add_picture" target="_blank">+ 创建图片</a-button>
        <!-- 批量抓取同样是管理员功能，和后端 /picture/upload/batch 的 @AuthCheck 对应 -->
        <a-button type="primary" href="/add_picture/batch" target="_blank" ghost>
          + 批量创建图片
        </a-button>
      </a-space>
    </a-flex>
    <div style="margin-bottom: 16px" />
    <!-- 搜索表单 -->
    <a-form layout="inline" :model="searchParams" @finish="doSearch">
      <a-form-item label="关键词" name="searchText">
        <a-input
          v-model:value="searchParams.searchText"
          placeholder="从名称和简介搜索"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="类型" name="category">
        <a-input v-model:value="searchParams.category" placeholder="请输入类型" allow-clear />
      </a-form-item>
      <a-form-item label="标签" name="tags">
        <a-select
          v-model:value="searchParams.tags"
          mode="tags"
          placeholder="请输入标签"
          style="min-width: 180px"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="审核状态" name="reviewStatus">
        <a-select
          v-model:value="searchParams.reviewStatus"
          style="min-width: 160px"
          placeholder="请选择审核状态"
          :options="PIC_REVIEW_STATUS_OPTIONS"
          allow-clear
        />
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
      :scroll="{ x: 1200 }"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'url'">
          <a-image :src="record.url" :width="100" />
        </template>
        <template v-else-if="column.dataIndex === 'tags'">
          <a-tag v-for="tag in parseTags(record.tags)" :key="tag">
            {{ tag }}
          </a-tag>
        </template>
        <template v-else-if="column.dataIndex === 'picInfo'">
          <div>格式：{{ record.picFormat ?? '未知' }}</div>
          <div>宽高：{{ record.picWidth ?? '未知' }} x {{ record.picHeight ?? '未知' }}</div>
          <div>宽高比：{{ record.picScale ?? '未知' }}</div>
          <div>大小：{{ formatSize(record.picSize) }}</div>
        </template>
        <template v-else-if="column.dataIndex === 'reviewMessage'">
          <div>审核状态：{{ reviewStatusText(record.reviewStatus) }}</div>
          <div>审核信息：{{ record.reviewMessage ?? '-' }}</div>
          <div>审核人：{{ record.reviewerId ?? '-' }}</div>
          <div v-if="record.reviewTime">审核时间：{{ formatTime(record.reviewTime) }}</div>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ formatTime(record.createTime) }}
        </template>
        <template v-else-if="column.dataIndex === 'editTime'">
          {{ formatTime(record.editTime) }}
        </template>
        <template v-else-if="column.dataIndex === 'action'">
          <a-space wrap>
            <!-- 后端 doPictureReview 会拒绝「审核状态未变化」的请求（请勿重复审核），
                 所以只在当前状态不是目标状态时才显示对应按钮 -->
            <a-button
              v-if="record.reviewStatus !== PIC_REVIEW_STATUS_ENUM.PASS"
              type="link"
              @click="handleReview(record, PIC_REVIEW_STATUS_ENUM.PASS)"
            >
              通过
            </a-button>
            <a-button
              v-if="record.reviewStatus !== PIC_REVIEW_STATUS_ENUM.REJECT"
              type="link"
              danger
              @click="handleReview(record, PIC_REVIEW_STATUS_ENUM.REJECT)"
            >
              拒绝
            </a-button>
            <a-button type="link" @click="doEdit(record.id)">编辑</a-button>
            <a-button type="link" danger @click="doDelete(record.id)">删除</a-button>
          </a-space>
        </template>
      </template>
    </a-table>
    <!-- 审核原因填写框：通过 / 拒绝共用，拒绝时模态框内自带警告和危险色确定按钮 -->
    <PictureReviewModal ref="reviewModalRef" :on-success="fetchData" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  deletePictureUsingPost,
  listPictureByPageUsingPost,
} from '@/api/pictureController.ts'
import {
  PIC_REVIEW_STATUS_ENUM,
  PIC_REVIEW_STATUS_MAP,
  PIC_REVIEW_STATUS_OPTIONS,
} from '@/constants/picture.ts'
import PictureReviewModal from '@/components/PictureReviewModal.vue'
import { Modal, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { formatSize } from '@/utils'

const router = useRouter()

// 审核模态框：通过 / 拒绝都走它，让管理员能填审核原因
const reviewModalRef = ref<InstanceType<typeof PictureReviewModal>>()

// 数据
const dataList = ref<API.Picture[]>([])
const total = ref(0)
const loading = ref(true)

// 搜索条件
const searchParams = reactive<API.PictureQueryRequest>({
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

// 获取数据
const fetchData = async () => {
  loading.value = true
  const params: API.PictureQueryRequest = {
    ...searchParams,
    // 只查公共图库的图：私有空间的图片属于用户自己的相册，不需要管理员审核，
    // 后端 listPictureByPage 不加这个条件会把所有人的空间图片都查出来
    nullSpaceId: true,
  }
  const res = await listPictureByPageUsingPost(params)
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
  searchParams.current = 1
  pagination.current = 1
  fetchData()
}

// 编辑
const doEdit = (id: number) => {
  router.push({
    path: '/add_picture',
    query: { id },
  })
}

/**
 * 删除
 *
 * 删除不可恢复，属于危险操作：先弹确认框，点「确认删除」才真正调接口。
 * onOk 返回 Promise，antd 会在接口返回前把确定按钮置为 loading，
 * 这样连点也不会重复提交。
 */
const doDelete = (id: number) => {
  if (!id) {
    return
  }
  Modal.confirm({
    title: '确认删除这张图片吗？',
    content: '删除后不可恢复，图片将从列表中移除。',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      const res = await deletePictureUsingPost({ id })
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
 * 审核图片：打开模态框让管理员填写审核原因，由模态框提交
 *
 * id 直接透传：它是后端 Long 在 JSON 里的字符串形态（雪花 id 超过
 * Number.MAX_SAFE_INTEGER），一旦 Number() 就会丢精度、审到错误的图上。
 * @param record 待审核的图片
 * @param reviewStatus 审核结果：PASS 通过 / REJECT 拒绝
 */
const handleReview = (record: API.Picture, reviewStatus: number) => {
  reviewModalRef.value?.openModal(record, reviewStatus)
}

/**
 * 表格列配置
 */
const columns = [
  {
    title: 'id',
    dataIndex: 'id',
    width: 80,
  },
  {
    title: '图片',
    dataIndex: 'url',
  },
  {
    title: '名称',
    dataIndex: 'name',
    width: 160,
  },
  {
    title: '简介',
    dataIndex: 'introduction',
    width: 200,
    ellipsis: true,
  },
  {
    title: '类型',
    dataIndex: 'category',
    width: 100,
  },
  {
    title: '标签',
    dataIndex: 'tags',
    width: 160,
  },
  {
    title: '图片信息',
    dataIndex: 'picInfo',
    width: 200,
  },
  {
    title: '用户 id',
    dataIndex: 'userId',
    width: 100,
  },
  {
    title: '审核信息',
    dataIndex: 'reviewMessage',
    width: 200,
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    width: 180,
  },
  {
    title: '编辑时间',
    dataIndex: 'editTime',
    width: 180,
  },
  {
    title: '操作',
    dataIndex: 'action',
    width: 140,
  },
]

/**
 * 后端把 tags 存成 JSON 字符串，这里解析回数组
 * @param tags
 */
const parseTags = (tags?: string): string[] => {
  if (!tags) {
    return []
  }
  try {
    const parsed = JSON.parse(tags)
    return Array.isArray(parsed) ? parsed : []
  } catch (error) {
    console.error('解析标签失败', tags, error)
    return []
  }
}

/**
 * 格式化时间
 * @param time
 */
const formatTime = (time?: string) => {
  return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '-'
}

/**
 * 审核状态文案
 * @param reviewStatus 后端返回的审核状态，理论上不会为空，兜底成 '-'
 */
const reviewStatusText = (reviewStatus?: number) => {
  return reviewStatus != null ? (PIC_REVIEW_STATUS_MAP[reviewStatus] ?? '-') : '-'
}

onMounted(() => {
  fetchData()
})
</script>

<style scoped>
#pictureManagePage {
  margin-bottom: 16px;
}
</style>
