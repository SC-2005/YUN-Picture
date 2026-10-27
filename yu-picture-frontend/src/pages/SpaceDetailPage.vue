<template>
  <div id="spaceDetailPage">
    <!-- 空间信息 -->
    <a-flex justify="space-between" align="center" :gap="16" wrap>
      <a-flex align="center" :gap="8" wrap>
        <h2 style="margin: 0">{{ space.spaceName ?? '空间' }}（私有空间）</h2>
        <!-- 空间级别标识：🌱 普通版 / 🚀 专业版 / 💎 旗舰版，配色由级别决定 -->
        <a-tag v-if="levelTag" :color="levelTag.color" style="margin: 0">
          {{ levelTag.icon }} {{ levelTag.text }}
        </a-tag>
      </a-flex>
      <a-space :size="16" wrap>
        <!-- 上传、编辑都只有空间创建人能操作，和后端 uploadPicture / editSpace 的校验对应 -->
        <a-button v-if="isOwner" type="primary" @click="openUploadModal"> + 创建图片 </a-button>
        <!-- 批量编辑只对私有空间开放：后端按 spaceId 校验空间归属，非创建人调用会被拒 -->
        <a-button v-if="isOwner" :icon="h(EditOutlined)" @click="doBatchEdit">批量编辑</a-button>
        <a-button v-if="isOwner" @click="doEditSpace">编辑空间</a-button>
        <!-- 空间分析：带上当前空间 id 跳到分析页，新开窗口看图表，不打断当前浏览的图片列表。
             spaceIdParam 是字符串形态的雪花 id（toApiId），拼到 URL 里不会丢精度 -->
        <a-button
          type="primary"
          ghost
          :icon="h(BarChartOutlined)"
          :href="`/space_analyze?spaceId=${spaceIdParam}`"
          target="_blank"
        >
          空间分析
        </a-button>
        <a-tooltip
          :title="`占用空间 ${formatSize(space.totalSize)} / ${formatSize(space.maxSize)}`"
        >
          <a-progress type="circle" :percent="usedPercent" :size="42" />
        </a-tooltip>
      </a-space>
    </a-flex>
    <div style="margin-bottom: 16px" />

    <!-- 没有权限（或未登录）时不渲染列表：接口会返回 40101/40100，这里给出明确的结果提示 -->
    <a-result
      v-if="errorMsg"
      status="403"
      :title="errorMsg"
      sub-title="私有空间内的图片仅空间创建人可查看"
    />
    <template v-else>
      <!-- 搜索表单：条件由子组件收集，这里只负责拼上 spaceId 去查 -->
      <PictureSearchForm @search="onSearch" />
      <!--
        按颜色搜索：和其他筛选条件不联动（后端是独立接口，只按颜色相似度排序返回），
        所以单独放在搜索表单下面。
        antdv 4 目前没有 ColorPicker 组件，用第三方 vue3-colorpicker
      -->
      <a-form-item label="按颜色搜索">
        <color-picker format="hex" @pureColorChange="onColorChange" />
      </a-form-item>
      <!-- 图片列表 -->
      <PictureGridList
        :data-list="dataList"
        :loading="loading"
        show-op
        :can-edit="isOwner"
        :can-delete="isOwner"
        :can-share="isOwner"
        @reload="onReload"
      />
      <!-- 分页 -->
      <a-pagination
        style="text-align: right"
        v-model:current="searchParams.current"
        v-model:page-size="searchParams.pageSize"
        :total="total"
        :show-total="() => `图片总数 ${total} / ${space.maxCount ?? '未知'}`"
        @change="onPageChange"
      />
    </template>
    <!-- 创建图片弹窗：上传 + 填信息都在空间页里就地完成，不再跳新页面。
         保存成功后走 onReload，列表和空间信息一起刷新（新图会占用额度） -->
    <PictureUploadModal ref="uploadModalRef" :space-id="spaceIdParam" :on-success="onReload" />
    <!-- 批量编辑弹窗：只在当前页的图片上生效，所以直接把 dataList 传进去 -->
    <BatchEditPictureModal
      ref="batchEditPictureModalRef"
      :picture-list="dataList"
      :space-id="spaceIdParam"
      @success="onBatchEditPictureSuccess"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { BarChartOutlined, EditOutlined } from '@ant-design/icons-vue'
import { ColorPicker } from 'vue3-colorpicker'
import 'vue3-colorpicker/style.css'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'
import {
  listPictureVoByPageUsingPost,
  searchPictureByColorUsingPost,
} from '@/api/pictureController.ts'
import BatchEditPictureModal from '@/components/BatchEditPictureModal.vue'
import PictureGridList from '@/components/PictureGridList.vue'
import PictureSearchForm from '@/components/PictureSearchForm.vue'
import PictureUploadModal from '@/components/PictureUploadModal.vue'
import { SPACE_LEVEL_MAP, getSpaceLevelColor, getSpaceLevelIcon } from '@/constants/space.ts'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { formatSize, toApiId } from '@/utils'

interface Props {
  id: string | number
}

const props = defineProps<Props>()

const router = useRouter()
const loginUserStore = useLoginUserStore()

const space = ref<API.SpaceVO>({})

/**
 * 空间 id 的接口形态
 *
 * 不能用 Number() 转换：雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度（见 toApiId 注释）
 */
const spaceIdParam = computed(() => toApiId(props.id))

/**
 * 空间级别标识
 *
 * 图标和配色统一放在 constants/space.ts 里维护（级别越高越"贵重"：🌱 → 🚀 → 💎）。
 * 级别拿不到时返回 null，模板里就不渲染这个标签，而不是显示一个空的灰标签
 */
const levelTag = computed(() => {
  const level = space.value.spaceLevel
  if (level == null || !SPACE_LEVEL_MAP[level]) {
    return null
  }
  return {
    icon: getSpaceLevelIcon(level),
    text: SPACE_LEVEL_MAP[level],
    color: getSpaceLevelColor(level),
  }
})

/** 数据 */
const dataList = ref<API.PictureVO[]>([])
const total = ref(0)
const loading = ref(false)
/** 加载失败的提示（主要是「没有空间权限」），有值时整个列表换成结果页 */
const errorMsg = ref('')

/**
 * 搜索条件
 *
 * 用 ref 而不是 reactive：搜索表单重置后要整体换成一套空条件，
 * ref 可以直接 searchParams.value = {...}，reactive 只能逐个字段改。
 * 后端 /picture/list/page/vo 限制 pageSize 最大 20，这里给 12 正好是 4 列 × 3 行
 */
const searchParams = ref<API.PictureQueryRequest>({
  current: 1,
  pageSize: 12,
  sortField: 'createTime',
  sortOrder: 'descend',
})

/**
 * 是否是空间创建人（现阶段私有空间的管理员就是创建人）
 *
 * 两侧都转成字符串再比：id 是雪花 id，在 JSON 里是字符串形态，类型上却声明成 number，
 * 用 === 直接比较会因为类型不一致而恒为 false
 */
const isOwner = computed(() => {
  const loginUserId = loginUserStore.loginUser.id
  if (!loginUserId) {
    return false
  }
  return String(space.value.userId) === String(loginUserId)
})

/**
 * 已用空间占比
 *
 * totalSize / maxSize 后端是 Long，JSON 里被序列化成了字符串，
 * 不转成数字直接算会得到 NaN；maxSize 理论上不会为 0，兜底 0 避免除零
 */
const usedPercent = computed(() => {
  const totalSize = Number(space.value.totalSize ?? 0)
  const maxSize = Number(space.value.maxSize ?? 0)
  if (!maxSize) {
    return 0
  }
  // 超出额度时也压回 100：a-progress 超过 100 会报警告，具体数值由 tooltip 展示
  return Math.min(100, Number(((totalSize * 100) / maxSize).toFixed(1)))
})

/** 获取空间详情 */
const fetchSpaceDetail = async () => {
  try {
    // 不能用 Number() 转换：雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度（见 toApiId 注释）
    const res = await getSpaceVoByIdUsingGet({ id: toApiId(props.id) })
    if (res.data.code === 0 && res.data.data) {
      space.value = res.data.data
    } else {
      message.error('获取空间详情失败，' + res.data.message)
    }
  } catch (error) {
    message.error('获取空间详情失败：' + (error as Error).message)
  }
}

/** 获取空间下的图片列表 */
const fetchData = async () => {
  errorMsg.value = ''
  // 未登录直接给提示，不发请求：带 spaceId 的查询接口会返回 40100，
  // 而全局响应拦截器对 40100 是直接跳登录页，会把用户从空间页弹走、连空间名都看不到
  if (!loginUserStore.loginUser.id) {
    errorMsg.value = '请先登录后查看空间内的图片'
    return
  }
  loading.value = true
  try {
    const res = await listPictureVoByPageUsingPost({
      spaceId: spaceIdParam.value,
      ...searchParams.value,
    })
    if (res.data.code !== 0 || !res.data.data) {
      // 别人的私有空间会走到这里，后端返回「没有空间权限」
      errorMsg.value = '获取数据失败，' + res.data.message
      dataList.value = []
      total.value = 0
      return
    }
    dataList.value = res.data.data.records ?? []
    total.value = res.data.data.total ?? 0
  } catch (error) {
    errorMsg.value = '获取数据失败，' + (error as Error).message
  } finally {
    loading.value = false
  }
}

/** 分页变化：v-model 已经改过 current / pageSize，这里只需要重新查询 */
const onPageChange = () => {
  fetchData()
}

/**
 * 搜索（搜索表单里点「搜索」/「重置」时触发）
 *
 * 整体替换 searchParams.value 而不是逐个字段改，是为了让「重置」也能生效：
 * 组件重置后传过来的对象里，各筛选字段都是 undefined，覆盖式合并正好把上一轮的条件清掉。
 * 换了筛选条件就回到第 1 页，否则可能停在一个已经不存在结果的页码上。
 */
const onSearch = (newSearchParams: API.PictureQueryRequest) => {
  searchParams.value = {
    ...searchParams.value,
    ...newSearchParams,
    current: 1,
  }
  fetchData()
}

/**
 * 按颜色搜索（取色器里选色时触发）
 *
 * 和其他筛选条件是两套：这个接口只收「颜色 + 空间 id」，按颜色相似度排序返回最多 12 条，
 * 带不了分页和关键词，所以不动 searchParams，直接把列表数据整体换成结果。
 *
 * pureColorChange 是组件内部 100ms 防抖后才抛出的事件，但拖动取色盘时仍会连发几次请求，
 * 用序号只认最后一次的响应 —— 否则先发的慢请求后到，会把列表刷回上一个颜色的结果。
 */
let colorSearchSeq = 0

const onColorChange = async (color: string) => {
  const seq = ++colorSearchSeq
  loading.value = true
  try {
    const res = await searchPictureByColorUsingPost({
      picColor: color,
      // 雪花 id 必须走 toApiId，直接传 props.id 会丢精度（见 spaceIdParam）
      spaceId: spaceIdParam.value,
    })
    if (seq !== colorSearchSeq) {
      return
    }
    if (res.data.code === 0 && res.data.data) {
      dataList.value = res.data.data
      // 颜色搜索没有分页，总数就按实际返回条数算，免得分页器还显示上一次的总数
      total.value = dataList.value.length
    } else {
      message.error('获取数据失败，' + res.data.message)
    }
  } catch (error) {
    if (seq === colorSearchSeq) {
      message.error('获取数据失败：' + (error as Error).message)
    }
  } finally {
    // 已经有更新的请求在飞就不要关 loading，否则会提前结束转圈
    if (seq === colorSearchSeq) {
      loading.value = false
    }
  }
}

/**
 * 列表刷新（删除图片后触发）
 *
 * 空间详情也要重新拉：删除会同时释放空间额度，容量进度条得跟着变
 */
const onReload = () => {
  fetchSpaceDetail()
  fetchData()
}

/** 创建图片弹窗：组件常驻在页面上，点按钮时通过 ref 调它的 openModal */
const uploadModalRef = ref<InstanceType<typeof PictureUploadModal>>()

const openUploadModal = () => {
  uploadModalRef.value?.openModal()
}

/** 批量编辑弹窗：同样是常驻组件，靠 ref 调 openModal */
const batchEditPictureModalRef = ref<InstanceType<typeof BatchEditPictureModal>>()

const doBatchEdit = () => {
  batchEditPictureModalRef.value?.openModal()
}

/**
 * 批量编辑成功
 *
 * 名称、分类、标签都被改了，重新查一次当前页（不重新拉空间详情：批量编辑不动额度和容量）
 */
const onBatchEditPictureSuccess = () => {
  fetchData()
}

/** 编辑空间：仅创建人可见，复用创建空间页的表单 */
const doEditSpace = () => {
  router.push({
    path: '/add_space',
    query: { id: space.value.id },
  })
}

onMounted(() => {
  fetchSpaceDetail()
  fetchData()
})

/** 在空间之间跳转时组件不会重建，必须监听 id 重新加载数据 */
watch(
  () => props.id,
  () => {
    fetchSpaceDetail()
    fetchData()
  },
)
</script>

<style scoped>
#spaceDetailPage {
  margin-bottom: 16px;
}
</style>
