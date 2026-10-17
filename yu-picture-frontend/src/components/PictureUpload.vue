<template>
  <div class="picture-upload">
    <a-upload
      list-type="picture-card"
      :show-upload-list="false"
      :custom-request="handleUpload"
      :before-upload="beforeUpload"
    >
      <img v-if="picture?.url" :src="picture?.url" alt="avatar" />
      <div v-else>
        <loading-outlined v-if="loading"></loading-outlined>
        <plus-outlined v-else></plus-outlined>
        <div class="ant-upload-text">点击或拖拽上传图片</div>
      </div>
    </a-upload>
  </div>
</template>
<script lang="ts" setup>
import { ref } from 'vue'
import { LoadingOutlined, PlusOutlined } from '@ant-design/icons-vue'
import type { UploadProps } from 'ant-design-vue'
import { message } from 'ant-design-vue'
import { uploadPictureUsingPost } from '@/api/pictureController.ts'

interface Props {
  picture?: API.PictureVO
  /** 要上传到的空间 id，不传表示上传到公共图库 */
  spaceId?: number
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

// 官方源码写的是 UploadProps['fileList'][number]，但 v4 里 fileList 是可选属性，这样索引会报 TS2537，
// 所以这里从 beforeUpload 的签名推导
type BeforeUploadFile = Parameters<NonNullable<UploadProps['beforeUpload']>>[0]

// 同理，customRequest 回调的入参也从 props 签名推导。
// 它的 file 是 Blob | string | RcFile，而 uploadPictureUsingPost 只要 File，
// 所以下面用 instanceof 收窄，而不是写 any
type UploadRequestOption = Parameters<NonNullable<UploadProps['customRequest']>>[0]

/**
 * 上传图片
 * @param file
 */
const handleUpload = async ({ file }: UploadRequestOption) => {
  // 非 File（理论到不了这里）直接跳过
  if (!(file instanceof File)) {
    return
  }
  loading.value = true
  try {
    const params: API.uploadPictureUsingPOSTParams = props.picture ? { id: props.picture.id } : {}
    // 空间 id 只在空间内上传时才有值；带上它后端会把图片存到 space 目录、并扣减空间额度
    if (props.spaceId != null) {
      params.spaceId = props.spaceId
    }
    const res = await uploadPictureUsingPost(params, {}, file)
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch (error) {
    console.error('图片上传失败', error)
    message.error('图片上传失败，' + (error as Error).message)
  }
  loading.value = false
}

const loading = ref<boolean>(false)

/**
 * 上传前的校验
 * @param file
 */
const beforeUpload = (file: BeforeUploadFile) => {
  // 校验图片格式
  const isJpgOrPng = file.type === 'image/jpeg' || file.type === 'image/png'
  if (!isJpgOrPng) {
    message.error('不支持上传该格式的图片，推荐 jpg 或 png')
  }
  // 校验图片大小
  const isLt2M = file.size / 1024 / 1024 < 2
  if (!isLt2M) {
    message.error('不能上传超过 2M 的图片')
  }
  return isJpgOrPng && isLt2M
}
</script>
<style scoped>
.picture-upload :deep(.ant-upload) {
  width: 100% !important;
  height: 100% !important;
  min-width: 152px;
  min-height: 152px;
}

/* 圆角：官方源码没有，按你之前的要求加的。
   overflow: hidden 让超出卡片的预览图被裁在圆角内，而不是溢出去 */
.picture-upload :deep(.ant-upload-select) {
  border-radius: 16px;
  overflow: hidden;
}

.picture-upload img {
  max-width: 100%;
  /* 官方是 max-height: 480px，比卡片本身还高（卡片只有 152px），长图会撑出去；改成 100% 才装得下 */
  max-height: 100%;
}

/* 官方写的是 .ant-upload-select-picture-card i，但 v4 的图标是 <span class="anticon">，
   不存在 <i> 元素，那条一直是死代码，这里换成真正生效的选择器 */
.picture-upload :deep(.ant-upload-select-picture-card .anticon) {
  font-size: 32px;
  color: #999;
}

.ant-upload-select-picture-card .ant-upload-text {
  margin-top: 8px;
  color: #666;
}
</style>
