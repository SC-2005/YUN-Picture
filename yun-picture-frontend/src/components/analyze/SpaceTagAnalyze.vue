<template>
  <div class="space-tag-analyze">
    <a-card title="空间图片标签分析">
      <v-chart :option="options" style="height: 320px; max-width: 100%" :loading="loading" />
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import VChart from 'vue-echarts'
import 'echarts'
// 词云不是 ECharts 内置图表，必须单独引入，否则运行时报 "series.type wordCloud is not exists"
import 'echarts-wordcloud'
import type { EChartsOption } from 'echarts'
import { message } from 'ant-design-vue'
import { getSpaceTagAnalyzeUsingPost } from '@/api/spaceAnalyzeController.ts'

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

// 图表数据。后端已经把 tags 里的 JSON 数组摊平并按次数倒序排好了
const dataList = ref<API.SpaceTagAnalyzeResponse[]>([])
// 加载状态
const loading = ref(true)

/**
 * 加载数据
 */
const fetchData = async () => {
  loading.value = true
  const res = await getSpaceTagAnalyzeUsingPost({
    queryAll: props.queryAll,
    queryPublic: props.queryPublic,
    spaceId: props.spaceId,
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
 */
watchEffect(() => {
  fetchData()
})

/**
 * 图表选项
 */
const options = computed<EChartsOption>(() => {
  const tagData = dataList.value.map((item) => ({
    name: item.tag ?? '',
    value: item.count ?? 0,
  }))

  return {
    // 用字符串模板而不是回调函数：' {b}' 是标签名、'{c}' 是次数，
    // 效果和 `${params.name}: ${params.value} 次` 一样，还省去给 params 标类型
    tooltip: {
      trigger: 'item',
      formatter: '{b}: {c} 次',
    },
    series: [
      {
        type: 'wordCloud',
        gridSize: 10,
        sizeRange: [12, 50], // 字体大小范围：次数越多字越大
        rotationRange: [-90, 90],
        shape: 'circle',
        textStyle: {
          // 每次渲染随机取色，让词云更有层次
          color: () =>
            `rgb(${Math.round(Math.random() * 255)}, ${Math.round(
              Math.random() * 255,
            )}, ${Math.round(Math.random() * 255)})`,
        },
        data: tagData,
      },
    ],
  }
})
</script>

<style scoped></style>
