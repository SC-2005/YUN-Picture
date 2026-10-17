<template>
  <div class="url-picture-upload">
    <a-input-group compact>
      <a-input
        v-model:value="fileUrl"
        style="width: calc(100% - 120px)"
        placeholder="请输入图片地址"
        allow-clear
      />
      <a-button type="primary" style="width: 120px" :loading="loading" @click="handleUpload">
        提交
      </a-button>
    </a-input-group>
    <div class="img-wrapper">
      <img v-if="picture?.url" :src="picture?.url" alt="avatar" />
    </div>
  </div>
</template>
<script lang="ts" setup>
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { uploadPictureByUrlUsingPost } from '@/api/pictureController.ts'

interface Props {
  picture?: API.PictureVO
  /** 要上传到的空间 id，不传表示上传到公共图库 */
  spaceId?: number
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

const fileUrl = ref<string>()
const loading = ref<boolean>(false)

/**
 * 通过 URL 上传图片
 *
 * 校验全在后端做（manager/upload/UrlPictureUpload.java），前端这边不重复实现，只把错误原样透传给用户：
 * 1. 必须是 http/https 协议，ftp 之类的会报「仅支持http和https协议的url」
 * 2. 会对该地址发一次 HEAD 请求，Content-Type 不在 jpeg/jpg/png/webp 里报「文件类型错误」
 * 3. 响应头里带 Content-Length 且超过 2MB 报「文件大小不能超过 2MB」
 *
 * 注意这些校验依赖目标站点返回响应头：对方服务器不返回 Content-Type / Content-Length，
 * 或者直接拒绝 HEAD 请求时，校验会被跳过（后端有意放宽），此时要等真正下载阶段才会失败。
 */
const handleUpload = async () => {
  loading.value = true
  try {
    // 传了 id 就是「重新上传」，后端会更新原图而不是新建；有 picture 说明是从修改页进来的
    const params: API.PictureUploadRequest = { fileUrl: fileUrl.value }
    if (props.picture) {
      params.id = props.picture.id
    }
    // 空间 id 只在空间内上传时才有值；带上它后端会把图片存到 space 目录、并扣减空间额度
    if (props.spaceId != null) {
      params.spaceId = props.spaceId
    }
    const res = await uploadPictureByUrlUsingPost(params)
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
</script>
<style scoped>
.url-picture-upload {
  margin-bottom: 16px;
}

.url-picture-upload img {
  max-width: 100%;
  max-height: 480px;
}

.url-picture-upload .img-wrapper {
  text-align: center;
  margin-top: 16px;
}
</style>
