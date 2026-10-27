<template>
  <div id="pictureDetailPage">
    <a-row :gutter="[16, 16]">
      <!-- 图片预览 -->
      <a-col :sm="24" :md="16" :xl="18">
        <a-image :src="picture.url" />
      </a-col>
      <!-- 图片信息区 -->
      <a-col :sm="24" :md="8" :xl="6">
        <a-card title="图片信息">
          <a-descriptions :column="1">
            <a-descriptions-item label="作者">
              {{ picture.user?.userName ?? '未知' }}
            </a-descriptions-item>
            <a-descriptions-item label="名称">
              {{ picture.name ?? '未命名' }}
            </a-descriptions-item>
            <a-descriptions-item label="简介">
              {{ picture.introduction ?? '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="分类">
              {{ picture.category ?? '默认' }}
            </a-descriptions-item>
            <a-descriptions-item label="标签">
              <a-tag v-for="tag in picture.tags ?? []" :key="tag">
                {{ tag }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="格式">
              {{ picture.picFormat ?? '未知' }}
            </a-descriptions-item>
            <a-descriptions-item label="宽度">
              {{ picture.picWidth ?? '未知' }}
            </a-descriptions-item>
            <a-descriptions-item label="高度">
              {{ picture.picHeight ?? '未知' }}
            </a-descriptions-item>
            <a-descriptions-item label="宽高比">
              {{ picture.picScale ?? '未知' }}
            </a-descriptions-item>
            <a-descriptions-item label="大小">
              {{ formatSize(picture.picSize) }}
            </a-descriptions-item>
          </a-descriptions>
          <!-- 审核快捷操作：仅管理员可见（对应后端 /picture/review 的 @AuthCheck(ADMIN_ROLE)） -->
          <template v-if="isAdmin">
            <a-divider style="margin: 16px 0" />
            <div style="margin-bottom: 8px">
              审核状态：
              <a-tag :color="reviewStatusColor">{{ reviewStatusText }}</a-tag>
            </div>
            <div v-if="reviewInfo.reviewMessage" style="margin-bottom: 8px">
              审核原因：{{ reviewInfo.reviewMessage }}
            </div>
            <a-space :size="8" style="margin-bottom: 8px">
              <!-- 后端 doPictureReview 会拒绝「审核状态未变化」的请求（请勿重复审核），
                   所以当前已是目标状态时按钮置灰 -->
              <a-button
                type="primary"
                :disabled="reviewInfo.reviewStatus === PIC_REVIEW_STATUS_ENUM.PASS"
                @click="openReview(PIC_REVIEW_STATUS_ENUM.PASS)"
              >
                审核通过
              </a-button>
              <a-button
                danger
                :disabled="reviewInfo.reviewStatus === PIC_REVIEW_STATUS_ENUM.REJECT"
                @click="openReview(PIC_REVIEW_STATUS_ENUM.REJECT)"
              >
                审核拒绝
              </a-button>
            </a-space>
          </template>
          <!-- wrap：加上分享后是 4 个按钮，窄屏放不下时换行，而不是被压扁 -->
          <a-flex justify="space-between" wrap :gap="16" style="margin-top: 16px">
            <a-button type="primary" ghost @click="doDownload">免费下载</a-button>
            <a-button :icon="h(ShareAltOutlined)" type="default" @click="doShare">分享</a-button>
            <a-button v-if="canEdit" type="default" @click="doEdit">编辑</a-button>
            <a-button v-if="canDelete" danger type="default" @click="doDelete">删除</a-button>
          </a-flex>
        </a-card>
      </a-col>
    </a-row>
    <!-- 审核原因填写框：通过 / 拒绝共用，拒绝时模态框内自带警告和危险色确定按钮 -->
    <PictureReviewModal ref="reviewModalRef" :on-success="onReviewSuccess" />
    <!-- 分享弹窗：链接在 doShare 里算好再打开 -->
    <ShareModal ref="shareModalRef" :link="shareLink" />
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue'
import {
  deletePictureUsingPost,
  getPictureByIdUsingGet,
  getPictureVoByIdUsingGet,
} from '@/api/pictureController.ts'
import { PIC_REVIEW_STATUS_ENUM, PIC_REVIEW_STATUS_MAP } from '@/constants/picture.ts'
import PictureReviewModal from '@/components/PictureReviewModal.vue'
import ShareModal from '@/components/ShareModal.vue'
import { ShareAltOutlined } from '@ant-design/icons-vue'
import { SPACE_PERMISSION_ENUM } from '@/constants/space.ts'
import { Modal, message } from 'ant-design-vue'
import { useRouter } from 'vue-router'
import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'
import { downloadImage, formatSize, toApiId } from '@/utils'

interface Props {
  id: string | number
}

const props = defineProps<Props>()

const router = useRouter()
const loginUserStore = useLoginUserStore()

/**
 * 是否管理员：审核快捷操作只给管理员看
 * 对应后端 PictureController.doPictureReview 的 @AuthCheck(ADMIN_ROLE)
 */
const isAdmin = computed(() => loginUserStore.loginUser.userRole === 'admin')

const picture = ref<API.PictureVO>({})

/**
 * 审核信息（仅管理员加载）
 *
 * 公开的 /picture/get/vo 返回的 PictureVO 里没有 reviewStatus / reviewMessage
 * —— 后端有意不向普通用户暴露审核信息。管理员要看当前审核状态，
 * 只能走管理员专属的 /picture/get（返回 Picture，带审核字段）。
 */
const reviewInfo = ref<API.Picture>({})

// 审核模态框：通过 / 拒绝都走它，让管理员能填审核原因
const reviewModalRef = ref<InstanceType<typeof PictureReviewModal>>()

// 获取图片详情
const fetchPictureDetail = async () => {
  if (!props.id) {
    message.error('图片 id 不存在')
    return
  }
  try {
    // 不能用 Number() 转换：雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度（见 toApiId 注释）
    const res = await getPictureVoByIdUsingGet({ id: toApiId(props.id) })
    if (res.data.code === 0 && res.data.data) {
      picture.value = res.data.data
    } else {
      message.error('获取图片详情失败，' + res.data.message)
    }
  } catch (error) {
    message.error('获取图片详情失败，' + (error as Error).message)
  }
}

/**
 * 获取审核信息（仅管理员）
 *
 * 单独一次请求，而不是塞进 fetchPictureDetail：这个接口带 @AuthCheck(ADMIN_ROLE)，
 * 普通用户（包括未登录的访客）调了只会拿到 403，没必要发。
 */
const fetchReviewInfo = async () => {
  if (!isAdmin.value || !props.id) {
    return
  }
  try {
    const res = await getPictureByIdUsingGet({ id: toApiId(props.id) })
    if (res.data.code === 0 && res.data.data) {
      reviewInfo.value = res.data.data
    }
  } catch (error) {
    // 审核信息拿不到不影响看图，不打断页面，也不弹错误提示
    console.error('获取审核信息失败', error)
  }
}

// 审核成功：详情和审核信息都要重新拉（审核状态变了）
const onReviewSuccess = () => {
  fetchPictureDetail()
  fetchReviewInfo()
}

/**
 * 通用权限检查函数
 *
 * 权限列表由后端 /picture/get/vo 根据图片所在的空间（公共图库 / 私有空间 / 团队空间）
 * 和当前用户的角色算好下发，前端只检查列表里有没有需要的权限码
 */
function createPermissionChecker(permission: string) {
  return computed(() => (picture.value.permissionList ?? []).includes(permission))
}

const hasEditPermission = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_EDIT)
const hasDeletePermission = createPermissionChecker(SPACE_PERMISSION_ENUM.PICTURE_DELETE)

/**
 * 是否是图片上传者（两边的 id 都按字符串比，雪花 id 在 JSON 里是字符串形态）
 */
const isOwner = computed(() => {
  const user = loginUserStore.loginUser
  if (!user.id || !picture.value.userId) {
    return false
  }
  return String(user.id) === String(picture.value.userId)
})

/**
 * 公共图库的兜底判断
 *
 * 后端的 SpaceUserAuthManager#getPermissionList 对公共图库只给管理员下发完整权限，
 * 普通用户上传的公共图片 permissionList 是空的。但后端鉴权（StpInterfaceImpl）对
 * 「公共图库 + 本人上传」是放行的，所以前端要补上「本人上传」这一条，
 * 否则普通用户会看不到自己公共图片的编辑 / 删除按钮。
 * 私有空间和团队空间不兜底，严格以 permissionList 为准。
 */
const hasPublicFallback = computed(
  () => picture.value.spaceId == null && (isAdmin.value || isOwner.value),
)

/** 是否有编辑权限：空间权限 ∪ 公共图库本人/管理员 */
const canEdit = computed(() => hasEditPermission.value || hasPublicFallback.value)

/** 是否有删除权限：空间权限 ∪ 公共图库本人/管理员 */
const canDelete = computed(() => hasDeletePermission.value || hasPublicFallback.value)

// 审核状态文案
const reviewStatusText = computed(() =>
  reviewInfo.value.reviewStatus != null
    ? (PIC_REVIEW_STATUS_MAP[reviewInfo.value.reviewStatus] ?? '-')
    : '-',
)

// 审核状态标签配色：通过绿、拒绝红、待审核橙
const reviewStatusColor = computed(() => {
  const map: Record<number, string> = {
    [PIC_REVIEW_STATUS_ENUM.PASS]: 'green',
    [PIC_REVIEW_STATUS_ENUM.REJECT]: 'red',
    [PIC_REVIEW_STATUS_ENUM.REVIEWING]: 'orange',
  }
  const status = reviewInfo.value.reviewStatus
  return status != null ? (map[status] ?? 'default') : 'default'
})

// 打开审核模态框
const openReview = (reviewStatus: number) => {
  reviewModalRef.value?.openModal(picture.value, reviewStatus)
}

// 下载
const doDownload = () => {
  downloadImage(picture.value.url, picture.value.name)
}

// ----- 分享 -----
const shareModalRef = ref<InstanceType<typeof ShareModal>>()
// 分享链接
const shareLink = ref<string>()

/**
 * 分享
 *
 * 分享的是「当前页面地址」的详情页链接，所以直接用 window.location 拼，
 * 不受雪花 id 精度问题影响（id 只做字符串拼接，不做任何数值运算）。
 * 详情页不限制登录，拿到链接的人可以直接打开。
 */
const doShare = () => {
  shareLink.value = `${window.location.protocol}//${window.location.host}/picture/${picture.value.id}`
  shareModalRef.value?.openModal()
}

// 编辑
const doEdit = () => {
  router.push({
    path: '/add_picture',
    query: {
      id: picture.value.id,
      // 私有空间的图片必须带上 spaceId，否则编辑页会把它当公共图库的图重新上传；
      // 公共图库图片的 spaceId 是 undefined，vue-router 会把空值参数直接丢掉
      spaceId: picture.value.spaceId,
    },
  })
}

/**
 * 删除
 *
 * 删除不可恢复，属于危险操作：先弹确认框，点「确认删除」才真正调接口。
 * onOk 返回 Promise，antd 会在接口返回前把确定按钮置为 loading，
 * 这样连点也不会重复提交。
 */
const doDelete = () => {
  const id = picture.value.id
  if (!id) {
    return
  }
  Modal.confirm({
    title: '确认删除这张图片吗？',
    content: '删除后不可恢复，图片将从列表中移除。',
    okText: '确认删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      const res = await deletePictureUsingPost({ id })
      if (res.data.code === 0) {
        message.success('删除成功')
        // 删除后回到首页
        router.push({
          path: '/',
        })
      } else {
        message.error('删除失败，' + res.data.message)
        // 失败时把 Promise 抛出去，让确认框保持打开，管理员能重试
        throw new Error(res.data.message)
      }
    },
  })
}

onMounted(() => {
  fetchPictureDetail()
  fetchReviewInfo()
})
</script>

<style scoped>
#pictureDetailPage {
  margin-bottom: 16px;
}

/* 让图片铺满左栏整行。
   antd 把 .ant-image 渲染成 display: inline-block，而 inline-block 会按内容的原始宽度收缩 ——
   图比容器窄时（比如用 URL 上传的小图）右边就空出一大条，看着像没居中；
   同时 antd 自带的 .ant-image-img { width: 100% } 在这种收缩的容器里也按不到容器宽度。
   改成块级之后，width: 100% 才真正按左栏宽度生效，图永远填满整行 */
#pictureDetailPage :deep(.ant-image) {
  display: block;
}

/* 限制预览图高度，避免长图把信息栏挤到屏幕外。
   约束必须打在 img 上 —— 源码把内联样式写在 <a-image> 上，会落到 .ant-image 这个 div，
   对 div 来说 object-fit 无效、max-height 也约束不到图本身，所以那里其实是不生效的。

   顺带：width: 100% 时 img 的盒子宽度定死、高度按比例算，加了 max-height 后盒子比例会和图对不上，
   靠 object-fit: contain 让图按原比例缩放着画在盒子里（不拉伸、默认居中） */
#pictureDetailPage :deep(.ant-image-img) {
  max-height: 600px;
  object-fit: contain;
}
</style>
