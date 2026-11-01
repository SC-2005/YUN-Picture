<template>
  <div class="space-user-analyze">
    <a-card title="空间图片用户分析">
      <v-chart :option="options" style="height: 320px; max-width: 100%" :loading="loading" />
      <!-- 卡片右上角放筛选表单：时间维度切换 + 按用户 id 过滤 -->
      <template #extra>
        <a-space>
          <a-segmented v-model:value="timeDimension" :options="timeDimensionOptions" />
          <a-input-search placeholder="请输入用户 id" enter-button="搜索用户" @search="doSearch" />
        </a-space>
      </template>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import VChart from 'vue-echarts'
import 'echarts'
import type { EChartsOption } from 'echarts'
import { message } from 'ant-design-vue'
import { getSpaceUserAnalyzeUsingPost } from '@/api/spaceAnalyzeController.ts'

/** 查询范围，和父页面的参数一一对应 */
interface Props {
  queryAll?: boolean
  queryPublic?: boolean
  spaceId?: number
}

const props = withDefaults(defineProps<Props>(), {
  queryAll: false,
  queryPublic: false,
})

// 时间维度：日 / 周 / 月
const timeDimension = ref<string>('day')
// 分段选择器组件的选项
const timeDimensionOptions = [
  {
    label: '日',
    value: 'day',
  },
  {
    label: '周',
    value: 'week',
  },
  {
    label: '月',
    value: 'month',
  },
]

// 按用户 id 过滤，空串表示不过滤（清空搜索框后点搜索就是这种情况）
const userId = ref<string>()
const doSearch = (value: string) => {
  userId.value = value.trim() || undefined
}

// 图表数据
const dataList = ref<API.SpaceUserAnalyzeResponse[]>([])
// 加载状态
const loading = ref(true)

/**
 * 加载数据
 */
const fetchData = async () => {
  loading.value = true
  const res = await getSpaceUserAnalyzeUsingPost({
    queryAll: props.queryAll,
    queryPublic: props.queryPublic,
    spaceId: props.spaceId,
    timeDimension: timeDimension.value,
    // 用户 id 是雪花 id，超过 Number.MAX_SAFE_INTEGER，不能 Number() 转换（会丢精度）。
    // 生成的类型声明写的是 number，这里按字符串原样传给后端，Jackson 能把它转回 Long
    userId: userId.value ? (userId.value as unknown as number) : undefined,
  })
  if (res.data.code === 0) {
    dataList.value = res.data.data ?? []
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
  loading.value = false
}

/**
 * 监听变量，参数改变时触发数据的重新加载
 *
 * 这里除了 props，还自动收集了 timeDimension 和 userId：
 * 切换日/周/月、点搜索都会直接触发重新请求，不需要手写 watch
 */
watchEffect(() => {
  fetchData()
})

/**
 * 图表选项
 */
const options = computed<EChartsOption>(() => {
  const periods = dataList.value.map((item) => item.period ?? '') // 时间区间
  const counts = dataList.value.map((item) => item.count ?? 0) // 上传数量

  return {
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: periods, name: '时间区间' },
    yAxis: { type: 'value', name: '上传数量' },
    series: [
      {
        name: '上传数量',
        type: 'line',
        data: counts,
        smooth: true, // 平滑折线
        emphasis: {
          focus: 'series',
        },
      },
    ],
  }
})
</script>

<style scoped></style>
