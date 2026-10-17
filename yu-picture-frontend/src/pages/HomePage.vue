<template>
  <div id="homePage">
    <!-- 搜索框 -->
    <div class="search-bar">
      <a-input-search
        v-model:value="searchText"
        placeholder="从海量图片中搜索"
        enter-button="搜索"
        size="large"
        allow-clear
        @search="onSearch"
        @change="onSearchInputChange"
      />
    </div>
    <!-- 分类 -->
    <a-tabs :active-key="category" @change="onCategoryChange">
      <a-tab-pane key="all" tab="全部" />
      <a-tab-pane v-for="item in categoryList" :tab="item" :key="item" />
    </a-tabs>
    <!-- 标签：选中状态用数组记录，不再依赖「下标与 tagList 对齐」 -->
    <div class="tag-bar">
      <span style="margin-right: 8px">标签：</span>
      <a-space :size="[0, 8]" wrap>
        <a-checkable-tag
          v-for="tag in allTagOptions"
          :key="tag"
          :checked="selectedTags.includes(tag)"
          @change="(checked: boolean) => onTagChange(tag, checked)"
        >
          {{ tag }}
        </a-checkable-tag>
      </a-space>
    </div>
    <!-- 图片列表（瀑布流 + 下拉加载，没有分页器） -->
    <PictureList
      :data-list="records"
      :loading="loading"
      :loading-more="loadingMore"
      :has-more="hasMore"
      @load-more="loadMore"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter } from 'vue-router'
import type { LocationQuery, LocationQueryRaw, LocationQueryValue } from 'vue-router'
import { message } from 'ant-design-vue'
import { listPictureTagCategoryUsingGet } from '@/api/pictureController.ts'
import {
  listPictureVoByCursorUsingPost,
  type PictureCursorPage,
  type PictureCursorQueryRequest,
} from '@/api-custom/pictureCursorController.ts'
import PictureList from '@/components/PictureList.vue'
import { usePictureFeedStore } from '@/stores/usePictureFeedStore.ts'

/** 每页条数。后端 /picture/list/cursor 限制最大 20 */
const PAGE_SIZE = 12
/** a-tabs 表示「全部分类」的哨兵值，绝不能进 URL、更不能进请求体 */
const ALL_CATEGORY = 'all'

const route = useRoute()
const router = useRouter()
const feedStore = usePictureFeedStore()
const { records, nextCursor, hasMore, scrollY } = storeToRefs(feedStore)

const loading = ref(false)
const loadingMore = ref(false)

/** 标签和分类选项。异步拉取，只影响勾选框的渲染，不参与请求参数的推导 */
const tagList = ref<string[]>([])
const categoryList = ref<string[]>([])

/**
 * 筛选条件的 UI 状态
 *
 * 注意：请求参数只从 URL 推导，不从这三个变量推导。
 * 一是因为 tagList 是异步拉的，watch(immediate) 在 setup 阶段就同步跑完了（早于 onMounted），
 * 那时 selectedTags 还是空的，带标签的 URL 首屏必然丢筛选；
 * 二是 URL 才是可以分享、可刷新、可后退的单一事实来源。
 */
const searchText = ref('')
const category = ref<string>(ALL_CATEGORY)
const selectedTags = ref<string[]>([])

/** 标签选项：服务端列表 + URL 里存在但不在列表中的标签（否则用户会被过滤却找不到原因、也取消不掉） */
const allTagOptions = computed(() => {
  const options = [...tagList.value]
  for (const tag of selectedTags.value) {
    if (!options.includes(tag)) {
      options.push(tag)
    }
  }
  return options
})

/** 一批筛选条件 */
interface FeedFilter {
  searchText: string
  /** 空字符串表示「全部分类」 */
  category: string
  tags: string[]
}

/** 从 query 里取单个字符串（值是 string | null | string[] 等多种形态） */
const pickStr = (value: LocationQueryValue | LocationQueryValue[] | undefined): string => {
  const one = Array.isArray(value) ? value[0] : value
  return typeof one === 'string' ? one.trim() : ''
}

/** 从 query 里取标签数组 */
const pickTags = (value: LocationQueryValue | LocationQueryValue[] | undefined): string[] => {
  const items = Array.isArray(value) ? value : value == null ? [] : [value]
  return [
    ...new Set(
      items
        .map((item) => (typeof item === 'string' ? item.trim() : ''))
        .filter((item) => item !== ''),
    ),
  ]
}

/**
 * 把 query 规范化成一个可比较的签名字符串
 *
 * 必须幂等：watch 里是「比较签名 -> 写 URL -> watch 再次触发」的闭环，
 * 如果规范化不幂等（重复的 key、顺序不定、空值处理不一致），就会无限循环。
 */
const canon = (query: Record<string, unknown>): string =>
  Object.keys(query)
    .sort()
    .map((key) => {
      const value = query[key]
      const items = (Array.isArray(value) ? value : [value])
        .filter((item): item is string => typeof item === 'string' && item !== '')
        .sort()
      return items.length > 0 ? `${key}=${items.join(',')}` : ''
    })
    .filter(Boolean)
    .join('&')

/** URL -> 筛选条件 */
const parseQuery = (query: LocationQuery): FeedFilter => ({
  searchText: pickStr(query.searchText),
  category: pickStr(query.category),
  tags: pickTags(query.tags),
})

/** 筛选条件 -> URL query（不写空值，避免出现 ?category= 这种噪音） */
const buildQuery = (filter: FeedFilter): LocationQueryRaw => {
  const query: LocationQueryRaw = {}
  const keyword = filter.searchText.trim()
  if (keyword) {
    query.searchText = keyword
  }
  // 哨兵值在这里被拦下，保证它既不会进 URL 也不会进请求体
  if (filter.category && filter.category !== ALL_CATEGORY) {
    query.category = filter.category
  }
  if (filter.tags.length > 0) {
    query.tags = filter.tags
  }
  return query
}

/** 筛选条件 -> 接口请求体；cursor 为 null 表示取第一页 */
const buildParams = (filter: FeedFilter, cursor: string | null): PictureCursorQueryRequest => {
  const params: PictureCursorQueryRequest = { pageSize: PAGE_SIZE, cursor }
  const keyword = filter.searchText.trim()
  if (keyword) {
    params.searchText = keyword
  }
  if (filter.category && filter.category !== ALL_CATEGORY) {
    params.category = filter.category
  }
  if (filter.tags.length > 0) {
    params.tags = filter.tags
  }
  return params
}

/** 把筛选条件同步到勾选框的渲染状态（只影响 UI，不触发请求） */
const applyToUI = (filter: FeedFilter) => {
  searchText.value = filter.searchText
  category.value = filter.category || ALL_CATEGORY
  selectedTags.value = [...filter.tags]
}

/**
 * 游标收敛：三个条件都成立才算「还有更多」
 *
 * 即使后端有 bug 也不该把浏览器拖进死循环 —— 没有游标、或这页没有数据，就不可能继续翻页
 */
const normalizeHasMore = (page: PictureCursorPage): boolean =>
  page.hasMore === true && (page.nextCursor ?? null) !== null && (page.records?.length ?? 0) > 0

/** 请求代次：筛选条件一变就作废在途的响应 */
let reqSeq = 0
let inflight: AbortController | null = null

/**
 * 是不是「请求被我们自己取消」而不是真失败
 *
 * 主动 abort 也会走 catch，不识别出来就会弹一个假的错误提示。
 * axios 1.x 抛 CanceledError（name/code 两种标识），浏览器原生 abort 抛 AbortError。
 */
const isCanceled = (error: unknown) => {
  const e = error as { name?: string; code?: string } | undefined
  return e?.name === 'CanceledError' || e?.code === 'ERR_CANCELED' || e?.name === 'AbortError'
}

/**
 * 加载第一页
 * @param filter 当前的筛选条件
 * @param key 这批筛选条件的签名，写进 store 用于判断缓存能否复用
 */
const loadFirstPage = async (filter: FeedFilter, key: string) => {
  const seq = ++reqSeq
  inflight?.abort()
  const controller = new AbortController()
  inflight = controller
  loading.value = true
  try {
    const res = await listPictureVoByCursorUsingPost(buildParams(filter, null), {
      signal: controller.signal,
    })
    if (seq !== reqSeq) {
      return
    }
    const page = res.data.data
    if (res.data.code !== 0 || !page) {
      message.error('获取数据失败，' + (res.data.message || '请重试'))
      feedStore.set(key, { records: [], nextCursor: null, hasMore: false })
      return
    }
    feedStore.set(key, {
      records: page.records ?? [],
      nextCursor: page.nextCursor ?? null,
      hasMore: normalizeHasMore(page),
    })
  } catch (error) {
    if (isCanceled(error) || seq !== reqSeq) {
      return
    }
    // 后端没起、超时、500 都会走到这里
    message.error('获取数据失败，' + (error as Error).message)
    feedStore.set(key, { records: [], nextCursor: null, hasMore: false })
  } finally {
    // finally 也要判代次：过期请求不能把新一代的 loading 关掉（或反过来）
    if (seq === reqSeq) {
      loading.value = false
    }
  }
}

/** 加载下一页并追加 */
const loadMore = async () => {
  const cursor = nextCursor.value
  if (!hasMore.value || loadingMore.value || loading.value || !cursor) {
    return
  }
  // 不自增代次：加载更多不改变筛选条件，只记录当前代次用于作废检测
  const seq = reqSeq
  loadingMore.value = true
  try {
    const res = await listPictureVoByCursorUsingPost(buildParams(parseQuery(route.query), cursor))
    if (seq !== reqSeq) {
      return
    }
    const page = res.data.data
    if (res.data.code !== 0 || !page) {
      message.error('加载失败，' + (res.data.message || '请重试'))
      hasMore.value = false
      return
    }
    // 去重：游标分页正常不会重复，但重复的 id 会让 v-for 的 key 冲突、页面上出现两张一样的图
    const seen = new Set(records.value.map((item) => String(item.id)))
    const fresh = (page.records ?? []).filter((item) => !seen.has(String(item.id)))
    records.value = [...records.value, ...fresh]
    nextCursor.value = page.nextCursor ?? null
    hasMore.value = normalizeHasMore(page)
    // 游标没前进 = 再翻还是同一批数据，就地停下（防死循环）
    if (page.nextCursor != null && String(page.nextCursor) === String(cursor)) {
      hasMore.value = false
    }
  } catch (error) {
    if (isCanceled(error) || seq !== reqSeq) {
      return
    }
    message.error('加载失败，' + (error as Error).message)
    hasMore.value = false
  } finally {
    if (seq === reqSeq) {
      loadingMore.value = false
    }
  }
}

/** 把筛选条件写进 URL。请求统一由 route.query 的 watcher 触发，形成单向数据流 */
const applyFilter = (patch: Partial<FeedFilter>) => {
  const next: FeedFilter = {
    searchText: searchText.value,
    category: category.value,
    tags: selectedTags.value,
    ...patch,
  }
  const query = buildQuery(next)
  // 规范化后相同就不写，否则会 watch -> replace -> watch 无限循环
  if (canon(query) === canon(route.query)) {
    return
  }
  router.replace({ path: route.path, query })
}

const onSearch = () => {
  applyFilter({ searchText: searchText.value })
}

/** allow-clear 只触发 change、不触发 search，这里补一刀；其余情况等回车，避免每敲一个字发一次请求 */
const onSearchInputChange = () => {
  if (searchText.value !== '') {
    return
  }
  applyFilter({ searchText: '' })
}

const onCategoryChange = (key: string | number) => {
  applyFilter({ category: String(key) })
}

const onTagChange = (tag: string, checked: boolean) => {
  const next = checked
    ? [...selectedTags.value, tag]
    : selectedTags.value.filter((item) => item !== tag)
  applyFilter({ tags: [...new Set(next)] })
}

/**
 * 是否由浏览器前进/后退触发
 *
 * 只有「后退回来」才需要恢复滚动位置；点导航栏重新进入首页应该从顶部开始。
 * vue-router 的 scrollBehavior 也能区分，但列表恢复在 setup 里就同步完成了，
 * 这里自己判断更直接、也不依赖 router 的滚动时机。
 */
let fromPopState = false
const onPopState = () => {
  fromPopState = true
}

const restoreScroll = () => {
  window.scrollTo({ top: scrollY.value, behavior: 'auto' })
}

watch(
  () => canon(route.query),
  (newKey) => {
    const filter = parseQuery(route.query)
    applyToUI(filter)

    // 筛选条件没变、缓存还在 -> 浏览器后退回来了，直接复用列表，不重新请求
    if (feedStore.matches(newKey)) {
      const popped = fromPopState
      fromPopState = false
      if (popped) {
        // 等 DOM 用恢复后的列表渲染完再滚，否则页面高度还不够、滚不到目标位置
        void nextTick(restoreScroll)
      }
      return
    }

    fromPopState = false
    window.scrollTo({ top: 0 })
    void loadFirstPage(filter, newKey)
  },
  { immediate: true },
)

/** 获取标签和分类选项。只拉选项，绝不在这里发列表请求 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagList.value = res.data.data.tagList ?? []
    categoryList.value = res.data.data.categoryList ?? []
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

onMounted(() => {
  window.addEventListener('popstate', onPopState)
  void getTagCategoryOptions()
})

onBeforeUnmount(() => {
  // 记下滚动位置，供从详情页返回时恢复
  scrollY.value = window.scrollY
  window.removeEventListener('popstate', onPopState)
})
</script>

<style scoped>
#homePage {
  margin-bottom: 16px;
}

#homePage .search-bar {
  max-width: 480px;
  margin: 0 auto 16px;
}

#homePage .tag-bar {
  margin-bottom: 16px;
}
</style>
