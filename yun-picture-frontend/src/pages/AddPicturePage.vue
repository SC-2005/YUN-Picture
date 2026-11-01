<template>
  <div id="addPicturePage">
    <h2 style="margin-bottom: 16px">
      {{ route.query?.id ? '修改图片' : '创建图片' }}
    </h2>
    <!-- 从空间详情页进来会带 spaceId，明确告诉用户这张图会存到哪个空间 -->
    <a-typography-paragraph v-if="spaceId" type="secondary">
      保存至空间：<a :href="`/space/${spaceId}`" target="_blank">{{ spaceId }}</a>
    </a-typography-paragraph>
    <!-- 选择上传方式：两个组件共用同一个 picture / onSuccess，切换 Tab 不会丢已上传的图 -->
    <a-tabs v-model:activeKey="uploadType">
      <a-tab-pane key="file" tab="文件上传">
        <!-- 图片上传组件 -->
        <PictureUpload :picture="picture" :spaceId="spaceId" :onSuccess="onSuccess" />
      </a-tab-pane>
      <!-- URL 上传走 /picture/upload/url，后端对应 manager/upload/UrlPictureUpload 那套校验 -->
      <a-tab-pane key="url" tab="URL 上传" force-render>
        <!-- URL 图片上传组件 -->
        <UrlPictureUpload :picture="picture" :spaceId="spaceId" :onSuccess="onSuccess" />
      </a-tab-pane>
    </a-tabs>
    <!-- 图片编辑：上传（或从修改页进来）之后才能编辑，不需要绑定具体是哪种上传方式 -->
    <div v-if="picture" class="edit-bar">
      <a-space wrap>
        <a-button :icon="h(EditOutlined)" @click="doEditPicture">编辑图片</a-button>
        <!-- AI 扩图：后端把图片交给阿里云百炼处理，属于异步任务，弹窗里轮询进度 -->
        <a-button type="primary" ghost :icon="h(FullscreenOutlined)" @click="doImageOutPainting">
          AI 扩图
        </a-button>
      </a-space>
      <ImageCropper
        ref="imageCropperRef"
        :image-url="picture?.url"
        :picture="picture"
        :space-id="spaceId"
        :space="space"
        :on-success="onPictureEditSuccess"
      />
      <ImageOutPainting
        ref="imageOutPaintingRef"
        :picture="picture"
        :space-id="spaceId"
        :on-success="onPictureEditSuccess"
      />
    </div>
    <!-- 图片信息表单 -->
    <a-form
      v-if="picture"
      name="pictureForm"
      layout="vertical"
      :model="pictureForm"
      @finish="handleSubmit"
    >
      <a-form-item name="name" label="名称">
        <a-input v-model:value="pictureForm.name" placeholder="请输入名称" allow-clear />
      </a-form-item>
      <a-form-item name="introduction" label="简介">
        <a-textarea
          v-model:value="pictureForm.introduction"
          placeholder="请输入简介"
          :auto-size="{ minRows: 2, maxRows: 5 }"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="category" label="分类">
        <a-auto-complete
          v-model:value="pictureForm.category"
          placeholder="请输入分类"
          :options="categoryOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item name="tags" label="标签">
        <a-select
          v-model:value="pictureForm.tags"
          mode="tags"
          placeholder="请输入标签"
          :options="tagOptions"
          allow-clear
        />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit" style="width: 100%">
          {{ route.query?.id ? '修改' : '创建' }}
        </a-button>
      </a-form-item>
    </a-form>
  </div>
</template>

<script setup lang="ts">
import PictureUpload from '@/components/PictureUpload.vue'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'
import ImageCropper from '@/components/ImageCropper.vue'
import ImageOutPainting from '@/components/ImageOutPainting.vue'
import { computed, h, onMounted, reactive, ref, watchEffect } from 'vue'
import { message } from 'ant-design-vue'
import {
  editPictureUsingPost,
  getPictureVoByIdUsingGet,
  listPictureTagCategoryUsingGet,
} from '@/api/pictureController.ts'
import { getSpaceVoByIdUsingGet } from '@/api/spaceController.ts'
import { useRoute, useRouter } from 'vue-router'
import { toApiId } from '@/utils'
import { EditOutlined, FullscreenOutlined } from '@ant-design/icons-vue'

const router = useRouter()
const route = useRoute()

const picture = ref<API.PictureVO>()
const pictureForm = reactive<API.PictureEditRequest>({})

/** 当前选中的上传方式：本地文件 / URL 地址 */
const uploadType = ref<'file' | 'url'>('file')

/**
 * 空间 id：从空间详情页（或空间内的图片卡片）跳进来时带上，图片会上传到该空间。
 *
 * 注意 spaceId 只在「上传」这一步起作用（后端在 uploadPicture 里给图片打上空间标记并扣额度），
 * 后面提交表单调的是 editPicture，那个接口不改 spaceId，所以不需要在提交参数里再传一遍。
 * 同样不能做 Number() 转换 —— 雪花 id 会丢精度。
 */
const spaceId = computed(() => {
  const id = route.query?.spaceId
  return id == null || id === '' ? undefined : toApiId(id)
})

/**
 * 当前图片所属空间
 *
 * 图片编辑组件靠它判断是不是团队空间，进而决定要不要开启协同编辑（WebSocket）。
 * 公共图库的图片没有 spaceId，不需要查，取不到值组件自然按「非团队空间」处理。
 */
const space = ref<API.SpaceVO>()

/**
 * 获取空间信息
 *
 * watchEffect 追踪 spaceId：从空间详情页跳进来时 spaceId 是可以直接读到的，
 * 但保留 watchEffect 能让「刷新页面 / 切换路由参数」这些场景也自动重新拉取。
 */
const fetchSpace = async () => {
  const id = spaceId.value
  if (id == null) {
    space.value = undefined
    return
  }
  const res = await getSpaceVoByIdUsingGet({ id })
  if (res.data.code === 0 && res.data.data) {
    space.value = res.data.data
  }
}

watchEffect(() => {
  fetchSpace()
})

/**
 * 图片上传成功
 * @param newPicture
 */
const onSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
  pictureForm.name = newPicture.name
}

/**
 * 提交表单
 * @param values
 */
const handleSubmit = async (values: API.PictureEditRequest) => {
  const pictureId = picture.value?.id
  if (!pictureId) {
    return
  }
  const res = await editPictureUsingPost({
    id: pictureId,
    ...values,
  })
  // 操作成功
  if (res.data.code === 0) {
    message.success(route.query?.id ? '保存成功' : '创建成功')
    // 跳转到图片详情页，而不是首页：
    // 非管理员创建的图片处于「待审核」，而首页列表只查审核通过的图，
    // 跳首页会让用户看不到刚上传的图片、以为创建失败了。
    // pictureId 是雪花 id 的字符串形态，直接拼进路径，不要 Number() 转换。
    router.push({
      path: `/picture/${pictureId}`,
    })
  } else {
    message.error('保存失败，' + res.data.message)
  }
}

const categoryOptions = ref<{ value: string; label: string }[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])

/**
 * 获取标签和分类选项
 */
const getTagCategoryOptions = async () => {
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => {
      return {
        value: data,
        label: data,
      }
    })
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

/**
 * 获取老数据
 */
const getOldPicture = async () => {
  // 获取到 id
  const id = route.query?.id
  if (!id) {
    return
  }
  const res = await getPictureVoByIdUsingGet({
    // 不能用 Number() 转换：雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度（见 toApiId 注释）
    id: toApiId(id),
  })
  if (res.data.code === 0 && res.data.data) {
    const data = res.data.data
    picture.value = data
    pictureForm.name = data.name
    pictureForm.introduction = data.introduction
    pictureForm.category = data.category
    pictureForm.tags = data.tags
  }
}

onMounted(() => {
  getTagCategoryOptions()
  getOldPicture()
})

// ----- 图片编辑弹窗 -----
const imageCropperRef = ref<InstanceType<typeof ImageCropper>>()
const imageOutPaintingRef = ref<InstanceType<typeof ImageOutPainting>>()

/**
 * 打开图片编辑弹窗
 *
 * 弹窗组件挂在 v-if="picture" 里面，等 picture 有了才有 ref，
 * 所以这里要判空，不然用户手快会报「Cannot read properties of undefined」。
 */
const doEditPicture = () => {
  imageCropperRef.value?.openModal()
}

/**
 * 打开 AI 扩图弹窗
 */
const doImageOutPainting = () => {
  imageOutPaintingRef.value?.openModal()
}

/**
 * 编辑成功（裁剪和 AI 扩图共用）
 *
 * 两个功能都是「改这一张图」：后端返回的就是换过图的那条记录（id 没变，url / 尺寸 / 大小变了），
 * 所以处理方式完全一样 —— 直接用返回值覆盖，页面上的预览和表单里的名称一起更新。
 */
const onPictureEditSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
  pictureForm.name = newPicture.name
}
</script>

<style scoped>
#addPicturePage {
  max-width: 720px;
  margin: 0 auto;
}

#addPicturePage .ant-upload-wrapper {
  text-align: center;
}

#addPicturePage .edit-bar {
  text-align: center;
  margin: 16px 0;
}
</style>
