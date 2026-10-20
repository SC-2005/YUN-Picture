<template>
  <div class="space-usage-analyze">
    <!-- 存储空间和图片数量各占一半，用仪表盘展示已用比例 -->
    <a-flex gap="middle">
      <a-card title="存储空间" style="width: 50%">
        <div style="height: 320px; text-align: center">
          <h3>
            {{ formatSize(data.usedSize) }} /
            {{ data.maxSize ? formatSize(data.maxSize) : '无限制' }}
          </h3>
          <!-- percent 必须给默认值：数据还没回来时 data.sizeUsageRatio 是 undefined，
               a-progress 收到 undefined 会报错，页面直接白屏 -->
          <a-progress type="dashboard" :percent="data.sizeUsageRatio ?? 0" />
        </div>
      </a-card>
      <a-card title="图片数量" style="width: 50%">
        <div style="height: 320px; text-align: center">
          <h3>{{ data.usedCount }} / {{ data.maxCount ?? '无限制' }}</h3>
          <a-progress type="dashboard" :percent="data.countUsageRatio ?? 0" />
        </div>
      </a-card>
    </a-flex>
  </div>
</template>

<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import { message } from 'ant-design-vue'
import { getSpaceUsageAnalyzeUsingPost } from '@/api/spaceAnalyzeController.ts'
import { formatSize } from '@/utils'

/**
 * 查询范围
 *
 * 三个参数由父页面（分析页）从 URL 上解析后统一传下来，组件自己不关心当前是谁在分析
 */
interface Props {
  queryAll?: boolean
  queryPublic?: boolean
  spaceId?: number
}

const props = withDefaults(defineProps<Props>(), {
  queryAll: false,
  queryPublic: false,
})

// 图表数据。queryAll / queryPublic 时后端不返回上限和比例（公共图库无上限），模板里都做了兜底
const data = ref<API.SpaceUsageAnalyzeResponse>({})

/**
 * 加载数据
 */
const fetchData = async () => {
  const res = await getSpaceUsageAnalyzeUsingPost({
    queryAll: props.queryAll,
    queryPublic: props.queryPublic,
    spaceId: props.spaceId,
  })
  if (res.data.code === 0 && res.data.data) {
    data.value = res.data.data
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

/**
 * 监听变量，改变时触发数据的重新加载
 *
 * 父页面切换查询范围时组件实例不会重建（只是 props 变了），所以不能用 onMounted，
 * watchEffect 会自动收集 props 里的三个字段，任何一个变化都重新请求
 */
watchEffect(() => {
  fetchData()
})
</script>

<style scoped></style>
