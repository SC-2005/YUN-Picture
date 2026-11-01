<template>
  <div id="spaceAnalyzePage">
    <!-- 标题直接体现当前分析范围，用户在哪个入口点进来一目了然 -->
    <h2>
      空间图库分析 -
      <span v-if="queryAll">全部空间</span>
      <span v-else-if="queryPublic">公共图库</span>
      <span v-else-if="spaceId">
        <a :href="`/space/${spaceId}`" target="_blank">空间 id：{{ spaceId }}</a>
      </span>
      <!-- 兜底：三个参数都没带时后端会报「没有指定图库」，这里先把范围说清楚 -->
      <span v-else>未指定范围</span>
    </h2>
    <div style="margin-bottom: 16px" />
    <!-- 栅格：小屏一行一个，中屏及以上一行两个 -->
    <a-row :gutter="[16, 16]">
      <!-- 空间使用分析 -->
      <a-col :xs="24" :md="12">
        <SpaceUsageAnalyze :spaceId="spaceId" :queryAll="queryAll" :queryPublic="queryPublic" />
      </a-col>
      <!-- 空间分类分析 -->
      <a-col :xs="24" :md="12">
        <SpaceCategoryAnalyze :spaceId="spaceId" :queryAll="queryAll" :queryPublic="queryPublic" />
      </a-col>
      <!-- 标签分析 -->
      <a-col :xs="24" :md="12">
        <SpaceTagAnalyze :spaceId="spaceId" :queryAll="queryAll" :queryPublic="queryPublic" />
      </a-col>
      <!-- 图片大小分段分析 -->
      <a-col :xs="24" :md="12">
        <SpaceSizeAnalyze :spaceId="spaceId" :queryAll="queryAll" :queryPublic="queryPublic" />
      </a-col>
      <!-- 用户上传行为分析 -->
      <a-col :xs="24" :md="12">
        <SpaceUserAnalyze :spaceId="spaceId" :queryAll="queryAll" :queryPublic="queryPublic" />
      </a-col>
      <!-- 空间使用排行分析：仅管理员可见，后端 rank 接口也会再校验一次 -->
      <a-col v-if="isAdmin" :xs="24" :md="12">
        <SpaceRankAnalyze :spaceId="spaceId" :queryAll="queryAll" :queryPublic="queryPublic" />
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import SpaceUsageAnalyze from '@/components/analyze/SpaceUsageAnalyze.vue'
import SpaceCategoryAnalyze from '@/components/analyze/SpaceCategoryAnalyze.vue'
import SpaceTagAnalyze from '@/components/analyze/SpaceTagAnalyze.vue'
import SpaceSizeAnalyze from '@/components/analyze/SpaceSizeAnalyze.vue'
import SpaceUserAnalyze from '@/components/analyze/SpaceUserAnalyze.vue'
import SpaceRankAnalyze from '@/components/analyze/SpaceRankAnalyze.vue'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { toApiId } from '@/utils'

const route = useRoute()

/**
 * 空间 id
 *
 * 从 URL 查询参数取，必须走 toApiId：雪花 id 超过 Number.MAX_SAFE_INTEGER，
 * Number() 转换会丢精度，后端就查不到这个空间了。
 * 没有 spaceId（queryAll / queryPublic 模式）时返回 undefined，接口里该字段会被省略
 */
const spaceId = computed(() => {
  const id = route.query?.spaceId
  return id ? toApiId(id) : undefined
})

// 是否分析全部空间（管理端入口：/space_analyze?queryAll=1）
const queryAll = computed(() => {
  return !!route.query?.queryAll
})

// 是否分析公共图库（管理端入口：/space_analyze?queryPublic=1）
const queryPublic = computed(() => {
  return !!route.query?.queryPublic
})

// 是否为管理员：只有管理员能看到「空间使用排行」，其他权限后端已经校验过了
const loginUserStore = useLoginUserStore()
const isAdmin = computed(() => {
  return loginUserStore.loginUser.userRole === 'admin'
})
</script>

<style scoped>
#spaceAnalyzePage {
  margin-bottom: 16px;
}
</style>
