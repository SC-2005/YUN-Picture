<template>
  <div ref="containerRef" class="picture-list">
    <!-- 首屏加载 -->
    <div v-if="loading && dataList.length === 0" class="list-status">
      <a-spin />
    </div>
    <!-- 空状态 -->
    <a-empty v-else-if="dataList.length === 0" description="暂无图片" />
    <!-- 瀑布流 -->
    <div v-else class="waterfall" :style="{ gap: GAP + 'px' }">
      <div
        v-for="(column, columnIndex) in columns"
        :key="columnIndex"
        class="waterfall-column"
        :style="{ gap: GAP + 'px' }"
      >
        <div
          v-for="card in column"
          :key="card.picture.id"
          class="picture-card"
          tabindex="0"
          @click="doClickPicture(card.picture)"
        >
          <!-- 高度在图片加载前就定好了，所以图片加载 / 加载失败都不会让整列跳动 -->
          <div class="cover" :style="{ height: card.coverHeight + 'px' }">
            <div v-if="failedMap[String(card.picture.id)]" class="cover-fallback">加载失败</div>
            <!-- 列表默认用缩略图，只有老数据thumbnailUrl为null时才回退到原图（缩略图与原图宽高比可能有出入，
                 但 cover 反正会裁切，预留高度按原图算不受影响） -->
            <img
              v-else
              :src="card.picture.thumbnailUrl ?? card.picture.url"
              :alt="card.picture.name ?? '图片'"
              loading="lazy"
              decoding="async"
              referrerpolicy="no-referrer"
              @error="failedMap[String(card.picture.id)] = true"
            />
            <!-- 悬浮遮罩：名称 / 分类 / 标签都折叠在图片内部 -->
            <div class="mask">
              <div class="mask-title">{{ card.picture.name ?? '未命名' }}</div>
              <div class="mask-tags">
                <a-tag color="green">{{ card.picture.category ?? '默认' }}</a-tag>
                <a-tag v-for="tag in card.picture.tags ?? []" :key="tag">
                  {{ tag }}
                </a-tag>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部哨兵：进入视口就自动加载下一页 -->
    <div ref="sentinelRef" class="sentinel">
      <a-spin v-if="loadingMore" />
      <!--
        IntersectionObserver 在打印、缩放、某些内嵌浏览器里并不可靠，
        所以保留下拉加载失败时的手动兜底入口，否则功能就是不可用的
      -->
      <a-button v-else-if="hasMore" type="link" @click="emit('load-more')">加载更多</a-button>
      <span v-else-if="dataList.length > 0" class="sentinel-end">没有更多了</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

interface Props {
  dataList?: API.PictureVO[]
  /** 首屏加载中 */
  loading?: boolean
  /** 正在加载下一页 */
  loadingMore?: boolean
  /** 是否还有更多数据 */
  hasMore?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  dataList: () => [],
  loading: false,
  loadingMore: false,
  hasMore: false,
})

const emit = defineEmits<{ 'load-more': [] }>()

const router = useRouter()

/** 列间距，同时用于计算列宽，必须和 CSS 里的 gap 一致 */
const GAP = 16
/** 单列最小宽度，决定了窄屏下的列数 */
const MIN_COL_WIDTH = 240
/**
 * 列数上限。BasicLayout 的 .content 没有 max-width，
 * 不做上限的话 2560px 宽屏会排出 10 列、每列 240px，卡片小得没法看
 */
const MAX_COLUMNS = 6
/** 拿不到 picWidth / picHeight 时的兜底宽高比（4:3） */
const DEFAULT_ASPECT = 4 / 3
const MIN_ASPECT = 0.4
const MAX_ASPECT = 2.5

const containerRef = ref<HTMLElement>()
const sentinelRef = ref<HTMLElement>()
/** 种子用窗口宽度，避免首帧先按 1 列渲染再跳到正确列数 */
const containerWidth = ref(typeof window === 'undefined' ? 0 : window.innerWidth)
const sentinelVisible = ref(false)
/** 记录加载失败的图片 id，失败时保留预留高度、只换成灰底（这是瀑布流不跳动的前提） */
const failedMap = ref<Record<string, boolean>>({})

const columnCount = computed(() =>
  Math.max(
    1,
    Math.min(MAX_COLUMNS, Math.floor((containerWidth.value + GAP) / (MIN_COL_WIDTH + GAP))),
  ),
)

const columnWidth = computed(
  () => (containerWidth.value - (columnCount.value - 1) * GAP) / columnCount.value,
)

/**
 * 取宽高比
 *
 * picWidth / picHeight 在 DDL 里可空，历史数据可能为 null；
 * 为 0 会算出 Infinity、为 null 会算出 NaN，而 height: NaNpx 是无效声明，
 * 盒子会塌成 0、等图片加载完再撑开，整列跟着跳。
 */
const aspectOf = (picture: API.PictureVO): number => {
  const width = picture.picWidth
  const height = picture.picHeight
  if (typeof width !== 'number' || typeof height !== 'number' || width <= 0 || height <= 0) {
    return DEFAULT_ASPECT
  }
  return Math.min(MAX_ASPECT, Math.max(MIN_ASPECT, width / height))
}

interface FeedCard {
  picture: API.PictureVO
  /** 图片区域的固定高度（px），按列宽和宽高比算出来，与内容无关 */
  coverHeight: number
}

/**
 * 把图片分到各列：每一张都放进「当前累计最矮」的那一列
 *
 * 用 computed 而不是维护 Map<id, column>：Map 在换筛选条件后不清理会残留旧 id。
 * 而且贪心分配对同一份 dataList 的结果是确定的 —— 前 N 张的分配只取决于前 N 张，
 * 所以下拉加载追加数据时老卡片不会位移。
 */
const columns = computed<FeedCard[][]>(() => {
  const result: FeedCard[][] = Array.from({ length: columnCount.value }, () => [])
  const heights = new Array<number>(columnCount.value).fill(0)
  const width = columnWidth.value
  if (width <= 0) {
    return result
  }
  for (const picture of props.dataList) {
    const coverHeight = Math.max(1, Math.round(width / aspectOf(picture)))
    // 并列时取最左，保证结果稳定
    let target = 0
    for (let i = 1; i < heights.length; i++) {
      if (heights[i] < heights[target]) {
        target = i
      }
    }
    result[target].push({ picture, coverHeight })
    heights[target] += coverHeight + GAP
  }
  return result
})

// 跳转至图片详情页
// picture.id 在运行时时字符串（后端 Long 序列化成了字符串），直接拼接即可，
// 千万不要在这里做 Number() 转换 —— 雪花 id 会丢精度
const doClickPicture = (picture: API.PictureVO) => {
  router.push({
    path: `/picture/${picture.id}`,
  })
}

let observer: IntersectionObserver | null = null
let resizeObserver: ResizeObserver | null = null

const tryLoadMore = () => {
  if (!sentinelVisible.value || !props.hasMore || props.loadingMore || props.loading) {
    return
  }
  emit('load-more')
}

const measure = () => {
  const el = containerRef.value
  if (el) {
    containerWidth.value = el.clientWidth
  }
}

onMounted(() => {
  const sentinel = sentinelRef.value
  if (sentinel) {
    observer = new IntersectionObserver(
      (entries) => {
        sentinelVisible.value = entries[0]?.isIntersecting ?? false
        tryLoadMore()
      },
      { rootMargin: '0px 0px 240px 0px' },
    )
    observer.observe(sentinel)
  }
  measure()
  if (containerRef.value) {
    resizeObserver = new ResizeObserver(measure)
    resizeObserver.observe(containerRef.value)
  }
})

onBeforeUnmount(() => {
  observer?.disconnect()
  resizeObserver?.disconnect()
})

/**
 * 追加数据后主动复检一次
 *
 * IntersectionObserver 只在「交叉状态发生变化」时回调。首屏的十几张图常常撑不满视口，
 * 哨兵一直处于 isIntersecting = true 的状态，之后就不会再触发回调了 ——
 * 页面会卡在第一页再也不加载。所以每次数据变化都要主动问一次「哨兵还在视口里吗」。
 */
watch(
  () => [props.dataList.length, props.loadingMore, props.loading, props.hasMore] as const,
  () => {
    void nextTick(tryLoadMore)
  },
  { flush: 'post' },
)
</script>

<style scoped>
.picture-list {
  min-height: 200px;
}

.list-status {
  display: flex;
  justify-content: center;
  padding: 48px 0;
}

.waterfall {
  display: flex;
  align-items: flex-start;
}

.waterfall-column {
  display: flex;
  flex-direction: column;
  /* flex-basis 0 + min-width 0：让浏览器平均分配列宽，并允许收缩到比内容更窄 */
  flex: 1 1 0;
  min-width: 0;
}

.picture-card {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  /* 图片没加载出来时也不会是一块白，看起来是有意的占位 */
  background: #f2f3f5;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.picture-card:hover,
.picture-card:focus-visible {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgb(0 0 0 / 16%);
  outline: none;
}

.cover {
  position: relative;
  width: 100%;
  overflow: hidden;
}

.cover img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  color: rgb(0 0 0 / 35%);
  font-size: 13px;
}

/* 悬浮遮罩：把额外信息折叠进图片内部，hover / 键盘聚焦时淡入 */
.mask {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px;
  opacity: 0;
  transition: opacity 0.2s ease;
  /* 从底部渐深的蒙层，保证白色文字在亮色图片上也读得清 */
  background: linear-gradient(to top, rgb(0 0 0 / 72%), rgb(0 0 0 / 0%) 60%);
}

.picture-card:hover .mask,
.picture-card:focus-visible .mask {
  opacity: 1;
}

.mask-title {
  color: #fff;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.mask-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  max-height: 24px;
  overflow: hidden;
}

/* a-tag 自带的浅色背景在深色蒙层上很突兀，压成半透明 */
.mask-tags :deep(.ant-tag) {
  margin-inline-end: 0;
  color: #fff;
  background: rgb(255 255 255 / 18%);
  border-color: transparent;
}

.sentinel {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 40px;
  padding: 12px 0;
}

.sentinel-end {
  color: rgb(0 0 0 / 45%);
  font-size: 13px;
}
</style>
