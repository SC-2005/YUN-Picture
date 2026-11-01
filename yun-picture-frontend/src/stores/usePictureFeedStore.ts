import { ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 首页图片流的缓存
 *
 * 从图片详情页返回时 HomePage 会被重新创建（项目没有用 keep-alive），
 * 如果什么都不缓存，列表会退回「只有第一页」—— 页面高度不够，
 * 浏览器的滚动位置恢复也就无处可去，用户得重新往下翻。
 *
 * 用筛选条件的签名 key 做校验：只有筛选条件完全一致时才复用缓存，
 * 否则用户会看到与当前筛选不符的列表（比重新加载更糟）。
 */
export const usePictureFeedStore = defineStore('pictureFeed', () => {
  /** 当前缓存对应的筛选条件签名 */
  const key = ref('')
  /** 已加载的图片 */
  const records = ref<API.PictureVO[]>([])
  /** 下一页游标。字符串 —— 见 api-custom/pictureCursorController.ts 的说明 */
  const nextCursor = ref<string | null>(null)
  /** 是否还有更多数据 */
  const hasMore = ref(false)
  /** 离开首页时的滚动位置 */
  const scrollY = ref(0)

  /**
   * 写入一批新的列表状态
   * @param nextKey 这批数据对应的筛选条件签名
   */
  const set = (
    nextKey: string,
    next: { records: API.PictureVO[]; nextCursor: string | null; hasMore: boolean },
  ) => {
    key.value = nextKey
    records.value = next.records
    nextCursor.value = next.nextCursor
    hasMore.value = next.hasMore
    scrollY.value = 0
  }

  /** 当前缓存是否就是这批筛选条件（且确实有数据可复用） */
  const matches = (target: string) => key.value === target && records.value.length > 0

  return { key, records, nextCursor, hasMore, scrollY, set, matches }
})
