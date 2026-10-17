<template>
  <a-modal
    v-model:open="visible"
    title="创建图片"
    width="720px"
    :confirm-loading="submitting"
    :ok-button-props="{ disabled: !picture }"
    ok-text="保存"
    cancel-text="取消"
    :destroy-on-close="true"
    @ok="handleOk"
    @cancel="closeModal"
  >
    <!-- 从空间详情页打开时会带 spaceId，明确告诉用户这张图存到哪个空间 -->
    <a-typography-paragraph v-if="spaceId" type="secondary">
      保存至空间：<a :href="`/space/${spaceId}`" target="_blank">{{ spaceId }}</a>
    </a-typography-paragraph>
    <!-- 选择上传方式：两个组件共用同一个 picture / onUploadSuccess，切换 Tab 不会丢已上传的图 -->
    <a-tabs v-model:activeKey="uploadType">
      <a-tab-pane key="file" tab="文件上传">
        <PictureUpload :picture="picture" :spaceId="spaceId" :onSuccess="onUploadSuccess" />
      </a-tab-pane>
      <!-- URL 上传走 /picture/upload/url，后端对应 manager/upload/UrlPictureUpload 那套校验 -->
      <a-tab-pane key="url" tab="URL 上传" force-render>
        <UrlPictureUpload :picture="picture" :spaceId="spaceId" :onSuccess="onUploadSuccess" />
      </a-tab-pane>
    </a-tabs>
    <!-- 上传那一步就已经入库了，这里只是补全信息：说清楚「关闭不等于撤销」，
         免得用户以为关掉弹窗图片就没了（和 AddPicturePage 的行为一致） -->
    <a-alert
      v-if="picture"
      type="success"
      show-icon
      message="图片已上传，填写信息后点「保存」"
      description="直接关闭也不会丢失：图片已经入库，只是名称沿用文件名。"
      style="margin-bottom: 16px"
    />
    <!-- 图片信息表单：没有独立的提交按钮，由弹窗的「保存」走同一套校验 -->
    <a-form v-if="picture" layout="vertical" :model="pictureForm" @finish="handleOk">
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
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { editPictureUsingPost, listPictureTagCategoryUsingGet } from '@/api/pictureController.ts'
import PictureUpload from '@/components/PictureUpload.vue'
import UrlPictureUpload from '@/components/UrlPictureUpload.vue'

interface Props {
  /** 要上传到的空间 id，不传表示上传到公共图库（可以当通用创建图片弹窗用） */
  spaceId?: number
  /** 保存成功后的回调，父组件用它刷新列表 / 空间信息 */
  onSuccess?: () => void
}

const props = defineProps<Props>()

// 是否可见
const visible = ref(false)
// 提交中：转圈期间禁用确定按钮，避免连点保存两次
const submitting = ref(false)

/** 已上传的图片。上传接口已经把它入库了，这里拿到的就是库里的那条记录 */
const picture = ref<API.PictureVO>()
/** 当前选中的上传方式：本地文件 / URL 地址 */
const uploadType = ref<'file' | 'url'>('file')
/** 图片信息表单，内容对应 editPicture 接口 */
const pictureForm = reactive<API.PictureEditRequest>({})

const categoryOptions = ref<{ value: string; label: string }[]>([])
const tagOptions = ref<{ value: string; label: string }[]>([])
/** 标签分类选项拉过一次就不再拉：它是全库聚合出来的，短时间不会变 */
const tagCategoryLoaded = ref(false)

/**
 * 图片上传成功
 * @param newPicture 新入库的图片
 */
const onUploadSuccess = (newPicture: API.PictureVO) => {
  picture.value = newPicture
  // 默认用上传时按文件名生成的名字，用户可以改
  pictureForm.name = newPicture.name
}

/**
 * 获取标签和分类选项（懒加载，第一次打开弹窗时才请求）
 */
const getTagCategoryOptions = async () => {
  if (tagCategoryLoaded.value) {
    return
  }
  const res = await listPictureTagCategoryUsingGet()
  if (res.data.code === 0 && res.data.data) {
    tagOptions.value = (res.data.data.tagList ?? []).map((data: string) => ({
      value: data,
      label: data,
    }))
    categoryOptions.value = (res.data.data.categoryList ?? []).map((data: string) => ({
      value: data,
      label: data,
    }))
    tagCategoryLoaded.value = true
  } else {
    message.error('获取标签分类列表失败，' + res.data.message)
  }
}

/**
 * 打开弹窗
 *
 * 每次打开都把上一次的状态清干净：组件是常驻在页面上的（不是点了按钮才创建），
 * 不清的话第二次打开会看到上次上传的图片和填过的信息
 */
const openModal = () => {
  picture.value = undefined
  pictureForm.name = undefined
  pictureForm.introduction = undefined
  pictureForm.category = undefined
  pictureForm.tags = undefined
  uploadType.value = 'file'
  visible.value = true
  getTagCategoryOptions()
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false
}

/**
 * 保存
 *
 * 上传接口只负责「图入库」，名称/简介/分类/标签是提交时另外调 editPicture 补的
 * —— 和 AddPicturePage 的分工完全一致
 */
const handleOk = async () => {
  const pictureId = picture.value?.id
  if (!pictureId) {
    message.warning('请先选择图片')
    return
  }
  submitting.value = true
  try {
    const res = await editPictureUsingPost({
      id: pictureId,
      ...pictureForm,
    })
    if (res.data.code === 0) {
      message.success('创建成功')
      closeModal()
      props.onSuccess?.()
    } else {
      message.error('保存失败，' + res.data.message)
    }
  } catch (error) {
    message.error('保存失败，' + (error as Error).message)
  } finally {
    submitting.value = false
  }
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>
