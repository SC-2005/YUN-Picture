<template>
  <div class="space-category-analyze">
    <a-card title="空间图片分类分析">
      <v-chart :option="options" style="height: 320px; max-width: 100%" :loading="loading" />
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watchEffect } from 'vue'
import VChart from 'vue-echarts'
import 'echarts'
import type { EChartsOption } from 'echarts'
import { message } from 'ant-design-vue'
import { getSpaceCategoryAnalyzeUsingPost } from '@/api/spaceAnalyzeController.ts'

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

// 图表数据。注意是数组：后端 /space/analyze/category 返回的是 List
const dataList = ref<API.SpaceCategoryAnalyzeResponse[]>([])
// 加载状态，交给 v-chart 显示骨架动画
const loading = ref(true)

/**
 * 加载数据
 */
const fetchData = async () => {
  loading.value = true
  const res = await getSpaceCategoryAnalyzeUsingPost({
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
 *
 * 用 computed 而不是一次性构造：数据是异步回来的，computed 会在 dataList 变化时自动重算，
 * v-chart 收到新的 option 后自己重绘
 */
const options = computed<EChartsOption>(() => {
  const categories = dataList.value.map((item) => item.category ?? '未分类')
  // count / totalSize 都是后端 Long，JSON 里被序列化成字符串（JsonConfig 的 ToStringSerializer）。
  // 数量是直接交给 ECharts 当数值用的，显式转成数字更稳；大小要先做除法，这里也一并转掉
  const countData = dataList.value.map((item) => Number(item.count ?? 0))
  // 后端 totalSize 是字节数，这里转成 MB 展示
  const sizeData = dataList.value.map((item) =>
    (Number(item.totalSize ?? 0) / (1024 * 1024)).toFixed(2),
  )

  return {
    tooltip: { trigger: 'axis' },
    legend: { data: ['图片数量', '图片总大小'], top: 'bottom' },
    xAxis: { type: 'category', data: categories },
    // 双 Y 轴：数量和大小量级差得多，共用一根轴的话数量柱会被压成一条线
    yAxis: [
      {
        type: 'value',
        name: '图片数量',
        axisLine: { show: true, lineStyle: { color: '#5470C6' } }, // 左轴颜色
      },
      {
        type: 'value',
        name: '图片总大小 (MB)',
        position: 'right',
        axisLine: { show: true, lineStyle: { color: '#91CC75' } }, // 右轴颜色
        splitLine: {
          lineStyle: {
            color: '#91CC75', // 调整网格线颜色
            type: 'dashed', // 线条样式：可选 'solid', 'dashed', 'dotted'
          },
        },
      },
    ],
    series: [
      { name: '图片数量', type: 'bar', data: countData, yAxisIndex: 0 },
      { name: '图片总大小', type: 'bar', data: sizeData, yAxisIndex: 1 },
    ],
  }
})
</script>

<style scoped></style>
