<template>
  <div class="picture-search-form">
    <!--
      搜索表单：组件只负责收集搜索条件，收集到就通过 search 事件抛给父组件，
      不碰数据请求、分页和 spaceId（那些都由使用它的页面负责）
    -->
    <a-form name="pictureSearchForm" layout="inline" :model="searchParams" @finish="doSearch">
      <!-- 基础搜索：最常用的三个条件，常驻不折叠 -->
      <a-form-item label="关键词" name="searchText">
        <a-input
          v-model:value="searchParams.searchText"
          placeholder="从名称和简介搜索"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="分类" name="category">
        <a-auto-complete
          v-model:value="searchParams.category"
          style="min-width: 180px"
          placeholder="请输入分类"
          :options="categoryOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item label="标签" name="tags">
        <a-select
          v-model:value="searchParams.tags"
          style="min-width: 180px"
          mode="tags"
          placeholder="请输入标签"
          :options="tagOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" html-type="submit">搜索</a-button>
          <!-- 不用 html-type="reset"：原生 reset 只把 input 恢复成它的初始值、不会同步 v-model，
               会和 Vue 里的数据对不上，所以统一走 doClear -->
          <a-button @click="doClear">重置</a-button>
        </a-space>
      </a-form-item>
    </a-form>

    <!-- 详细搜索：默认收起，只留一行标题，展开后是完整的筛选条件 -->
    <a-collapse v-model:active-key="activeKeys" :bordered="false">
      <a-collapse-panel key="advanced">
        <template #header>
          <a-space :size="8">
            <span>详细搜索</span>
            <!-- 收起时也能看出「还有条件在生效」，避免忘了自己设过什么 -->
            <a-tag v-if="advancedActiveCount > 0" color="blue" style="margin: 0">
              {{ advancedActiveCount }} 项已设置
            </a-tag>
          </a-space>
        </template>
        <!-- 同样绑定 @finish：在详细条件里按回车也能直接搜 -->
        <a-form layout="inline" :model="searchParams" @finish="doSearch">
          <a-form-item label="日期">
            <a-range-picker
              v-model:value="dateRange"
              style="width: 380px"
              show-time
              :placeholder="['编辑开始时间', '编辑结束时间']"
              format="YYYY/MM/DD HH:mm:ss"
              :presets="rangePresets"
              @change="onRangeChange"
            />
          </a-form-item>
          <a-form-item label="名称" name="name">
            <a-input v-model:value="searchParams.name" placeholder="请输入名称" allow-clear />
          </a-form-item>
          <a-form-item label="简介" name="introduction">
            <a-input
              v-model:value="searchParams.introduction"
              placeholder="请输入简介"
              allow-clear
            />
          </a-form-item>
          <a-form-item>
            <template #label>
              <a-tooltip
                title="选一个预设，会自动填入后面两个「最大宽高」，宽高都不超过预设值的图片才会命中"
              >
                <span>尺寸 <question-circle-outlined /></span>
              </a-tooltip>
            </template>
            <a-select
              :value="selectedSizeKey"
              :options="sizeOptions"
              style="min-width: 140px"
              placeholder="请选择尺寸"
              allow-clear
              @change="onSizeChange"
            />
          </a-form-item>
          <a-form-item label="最大宽度" name="picWidthMax">
            <a-input-number
              v-model:value="searchParams.picWidthMax"
              :min="1"
              placeholder="像素"
              style="width: 130px"
            />
          </a-form-item>
          <a-form-item label="最大高度" name="picHeightMax">
            <a-input-number
              v-model:value="searchParams.picHeightMax"
              :min="1"
              placeholder="像素"
              style="width: 130px"
            />
          </a-form-item>
          <a-form-item label="格式" name="picFormat">
            <a-select
              v-model:value="searchParams.picFormat"
              :options="PIC_FORMAT_OPTIONS"
              style="min-width: 140px"
              placeholder="请选择格式"
              allow-clear
            />
          </a-form-item>
        </a-form>
      </a-collapse-panel>
    </a-collapse>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import { message } from 'ant-design-vue'
import { QuestionCircleOutlined } from '@ant-design/icons-vue'
import { listPictureTagCategoryUsingGet } from '@/api/pictureController.ts'
import { PIC_FORMAT_OPTIONS, PIC_SIZE_PRESETS } from '@/constants/picture.ts'

/**
 * 搜索条件通过事件抛给父组件
 *
 * 教程里是 `:onSearch="onSearch"` 这种「把函数当 prop 传」的写法，
 * 这里改成 emit —— 父组件写 `@search="onSearch"` 即可，和本项目其它子组件（如 PictureGridList）保持一致。
 */
const emit = defineEmits<{ search: [searchParams: API.PictureQueryRequest] }>()

/**
 * 搜索条件
 *
 * 用 reactive 而不是 ref：模板里几十处 v-model 直接写 searchParams.xxx 更顺手。
 * 代价是重置时不能整体赋值成 {}（那会换掉对象、子表单还绑在旧对象上），
 * 只能用一份空值模板逐个字段覆盖，见 doClear。
 */
const searchParams = reactive<API.PictureQueryRequest>({})

/** 「详细搜索」折叠面板当前展开的项。空数组 = 收起（默认就是收起，让表单看起来只有一行） */
const activeKeys = ref<(string | number)[]>([])

// ---------- 分类 / 标签选项 ----------

/**
 * 分类、标签两个表单项的候选值，和创建图片页共用同一个接口
 *
 * 类型是「label + value」的选项对象，不是字符串数组 —— 教程源码里声明成 string[] 却塞了对象，
 * 运行时靠 antd 宽容处理才没报错，这里直接写成正确的类型。
 */
const categoryOptions = ref<{ label: string; value: string }[]>([])
const tagOptions = ref<{ label: string; value: string }[]>([])

const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    // 接口给的是字符串数组，转成下拉组件接受的选项格式
    tagOptions.value = (res.data.data.tagList ?? []).map((data) => ({ label: data, value: data }))
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data) => ({
      label: data,
      value: data,
    }))
  } else {
    message.error('加载标签分类失败，' + res.data.message)
  }
}

onMounted(() => {
  getTagCategoryOptions()
})

// ---------- 日期 ----------

/** 日期范围的值。区间是 [Dayjs, Dayjs]，清空后是 null */
type DateRangeValue = [Dayjs | null | undefined, Dayjs | null | undefined] | null

/**
 * 日期选择器的值
 *
 * 必须单独存：RangePicker 拿到的是 dayjs 对象，而 searchParams 里要提交的是两个时间字段，
 * 两者没法互相推导（清空时前者是 null、后者要清成 undefined）
 */
const dateRange = ref<DateRangeValue>(null)

/**
 * 日期范围变化
 *
 * 发 toISOString() 而不是教程里的 toDate()：JSON.stringify 对 Date 也是调 toISOString，
 * 所以两者实际发出去的字符串完全一样；但 startEditTime 在类型里声明的是 string，
 * 直接赋 Date 过不了类型检查。
 */
const onRangeChange = (dates: DateRangeValue) => {
  const start = dates?.[0]
  const end = dates?.[1]
  if (!start || !end) {
    searchParams.startEditTime = undefined
    searchParams.endEditTime = undefined
    return
  }
  searchParams.startEditTime = start.toISOString()
  searchParams.endEditTime = end.toISOString()
}

/** 时间范围预设，对应教程里「过去 N 天」那组快捷选项 */
const rangePresets = ref([
  { label: '过去 7 天', value: [dayjs().add(-7, 'd'), dayjs()] },
  { label: '过去 14 天', value: [dayjs().add(-14, 'd'), dayjs()] },
  { label: '过去 30 天', value: [dayjs().add(-30, 'd'), dayjs()] },
  { label: '过去 90 天', value: [dayjs().add(-90, 'd'), dayjs()] },
])

// ---------- 尺寸预设 ----------

const sizeOptions = PIC_SIZE_PRESETS.map((preset) => ({
  label: preset.label,
  value: preset.value,
}))

/**
 * 当前选中的尺寸预设
 *
 * 从最大宽高反推，而不是另外存一个 ref：用户手动改掉输入框里的数值之后，
 * 下拉框会自动回到「未选中」，不会出现「显示小、实际按别的尺寸查」这种错位。
 */
const selectedSizeKey = computed(() => {
  return PIC_SIZE_PRESETS.find(
    (preset) =>
      preset.maxWidth === searchParams.picWidthMax &&
      preset.maxHeight === searchParams.picHeightMax,
  )?.value
})

/**
 * 选择尺寸预设：把两个「最大宽高」一起填上（填完在输入框里能直接看到），
 * 清空预设时两个都清掉，避免留下「看不见的条件」
 */
const onSizeChange = (value?: string) => {
  const preset = PIC_SIZE_PRESETS.find((item) => item.value === value)
  searchParams.picWidthMax = preset?.maxWidth
  searchParams.picHeightMax = preset?.maxHeight
}

// ---------- 搜索 / 重置 ----------

/** 详细搜索里已填写的条件数量 */
const advancedActiveCount = computed(() => {
  const fields: (keyof API.PictureQueryRequest)[] = [
    'name',
    'introduction',
    'picWidthMax',
    'picHeightMax',
    'picFormat',
    'startEditTime',
  ]
  return fields.filter((key) => {
    const value = searchParams[key]
    return value !== undefined && value !== null && value !== ''
  }).length
})

/**
 * 提交搜索
 *
 * 发的是展开后的普通对象（快照），不是 reactive 本身：父组件拿到的应该是一份定死的条件，
 * 而不是「以后还会跟着表单变的活引用」。
 */
const doSearch = () => {
  emit('search', { ...searchParams })
}

/**
 * 重置
 *
 * 空值模板里要把表单用到的字段列全，新增搜索字段时记得同步补上，否则重置清不掉。
 * 数字输入框没有 allow-clear，也只能靠这个清。
 */
const EMPTY_SEARCH_PARAMS: API.PictureQueryRequest = {
  searchText: undefined,
  category: undefined,
  tags: undefined,
  name: undefined,
  introduction: undefined,
  picWidthMax: undefined,
  picHeightMax: undefined,
  picFormat: undefined,
  startEditTime: undefined,
  endEditTime: undefined,
}

const doClear = () => {
  Object.assign(searchParams, EMPTY_SEARCH_PARAMS)
  // 日期得单独清：它的值挂在 dateRange 上，不在 searchParams 里
  dateRange.value = null
  // 清空后立刻按空条件查一次，列表和表单保持一致
  doSearch()
}
</script>

<style scoped>
.picture-search-form {
  margin-bottom: 16px;
}

/* 换行后两行挤在一起，给每个表单项补上边距（inline 布局默认只有右间距） */
.picture-search-form .ant-form-item {
  margin-top: 16px;
}

/* 折叠面板自己带一层灰底，和页面背景不一样，看起来像块补丁；去掉它，只留标题和箭头 */
.picture-search-form :deep(.ant-collapse) {
  background: transparent;
}

/* 标题和内容去掉左右内边距，让详细搜索和上面的基础搜索左对齐 */
.picture-search-form :deep(.ant-collapse > .ant-collapse-item > .ant-collapse-header),
.picture-search-form
  :deep(.ant-collapse > .ant-collapse-item > .ant-collapse-content > .ant-collapse-content-box) {
  padding-inline: 0;
}
</style>
