<template>
  <div class="space-rank-analyze">
    <a-card title="空间使用排行分析">
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
import { getSpaceRankAnalyzeUsingPost } from '@/api/spaceAnalyzeController.ts'

// 图表数据。后端返回的是空间实体列表，只 select 了 id / spaceName / userId / totalSize
const dataList = ref<API.Space[]>([])
// 加载状态
const loading = ref(true)

/**
 * 加载数据
 *
 * 排行接口只认 topN（后端默认 10），不接受 queryAll / queryPublic / spaceId，
 * 所以这里不需要父页面传范围参数
 */
const fetchData = async () => {
  loading.value = true
  const res = await getSpaceRankAnalyzeUsingPost({
    topN: 10, // 后端默认就是 10，这里显式写出来方便以后加「前 N 名」的输入框
  })
  if (res.data.code === 0) {
    dataList.value = res.data.data ?? []
  } else {
    // 普通用户调用会走到这里：后端对排行接口做了管理员校验（NO_AUTH_ERROR）
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
  const spaceNames = dataList.value.map((item) => item.spaceName ?? '未命名空间')
  // 后端 totalSize 是字节数，转成 MB 展示；Long 在 JSON 里是字符串，除法时会自动转数字
  const usageData = dataList.value.map((item) => ((item.totalSize ?? 0) / (1024 * 1024)).toFixed(2))

  return {
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: spaceNames,
    },
    yAxis: {
      type: 'value',
      name: '空间使用量 (MB)',
    },
    series: [
      {
        name: '空间使用量 (MB)',
        type: 'bar',
        data: usageData,
        itemStyle: {
          color: '#5470C6', // 自定义柱状图颜色
        },
      },
    ],
  }
})
</script>

<style scoped></style>
