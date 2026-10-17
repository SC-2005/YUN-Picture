<template>
  <a-modal
    class="image-out-painting"
    v-model:open="visible"
    title="AI 扩图"
    :footer="false"
    @cancel="closeModal"
  >
    <!-- 左原图右结果：对照着看才好决定要不要采用 -->
    <a-row :gutter="16">
      <a-col :span="12">
        <h4>原始图片</h4>
        <img :src="picture?.url" :alt="picture?.name" />
      </a-col>
      <a-col :span="12">
        <h4>扩图结果</h4>
        <img v-if="resultImageUrl" :src="resultImageUrl" :alt="picture?.name" />
        <!-- 结果没出来之前给个占位：任务要跑十几秒到几十秒，空着一块容易让人以为坏了 -->
        <div v-else class="image-out-painting-status">
          <a-spin v-if="taskId" />
          <span>{{ taskId ? '生成中，请稍候…' : '还没有结果' }}</span>
        </div>
      </a-col>
    </a-row>
    <div style="margin-bottom: 16px" />
    <a-flex justify="center" gap="16">
      <!-- 任务跑着的时候按钮转圈，顺带禁止重复点击：点一次就是一次真实的 AI 调用 -->
      <a-button type="primary" ghost :loading="!!taskId" @click="createTask">生成图片</a-button>
      <a-button v-if="resultImageUrl" type="primary" :loading="uploadLoading" @click="handleUpload">
        应用结果
      </a-button>
    </a-flex>
    <div style="margin-bottom: 8px" />
    <a-typography-text type="secondary">
      扩图由 AI 生成、耗时较长；结果地址是临时的，点「应用结果」才会保存到平台。
    </a-typography-text>
  </a-modal>
</template>

<script lang="ts" setup>
import { onUnmounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  createPictureOutPaintingTaskUsingPost,
  getPictureOutPaintingTaskUsingGet,
  uploadPictureByUrlUsingPost,
} from '@/api/pictureController.ts'

interface Props {
  /** 要扩图的图片，必须已经有 id（扩图是拿平台上的图片去做的，不是本地文件） */
  picture?: API.PictureVO
  /** 结果要保存到的空间 id，不传表示公共图库 */
  spaceId?: number
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

// 是否可见
const visible = ref(false)
// 扩图结果图地址（阿里云返回的临时地址，过期就取不到了）
const resultImageUrl = ref<string>()
// 任务 id：有值说明任务还在跑
const taskId = ref<string>()

/**
 * 创建扩图任务
 *
 * 扩图是异步任务（出图慢，官方接口本身就是异步的），这里提交完拿到任务 id 后开始轮询查状态。
 * 提交成功后就把按钮锁住（loading 跟着 taskId 走），避免用户连点提交出多个任务。
 */
const createTask = async () => {
  const pictureId = props.picture?.id
  if (!pictureId) {
    message.error('图片还没准备好，无法扩图')
    return
  }
  try {
    // 重新生成时先把上一次的结果清掉，免得旧结果留在旁边让人误会
    resultImageUrl.value = undefined
    const res = await createPictureOutPaintingTaskUsingPost({
      // picture.id 后端返回的就是字符串（Long 用 ToStringSerializer 序列化），原样传即可，
      // 千万不要 Number() —— 雪花 id 超过 Number.MAX_SAFE_INTEGER，转换会丢精度
      pictureId: pictureId,
      // top/bottom/left/rightScale：上、下、左、右四个方向各自向外扩展的倍数，
      // 1.0 表示这个方向不扩展（官方文档范围 1.0 ~ 2.0）。
      // 这里四边都固定 1.5 倍：四周等比往外扩一圈，效果直观，也不会因为图太大生成很久。
      // 字段名对应后端 CreateOutPaintingTaskRequest.Parameters（@Alias 映射到 top_scale 等）
      parameters: {
        topScale: 1.5,
        bottomScale: 1.5,
        leftScale: 1.5,
        rightScale: 1.5,
      },
    })
    const newTaskId = res.data.data?.output?.taskId
    if (res.data.code === 0 && newTaskId) {
      message.success('任务已提交，正在生成，请稍候')
      taskId.value = newTaskId
      startPolling()
    } else {
      message.error('创建任务失败，' + res.data.message)
    }
  } catch (error) {
    console.error('创建扩图任务失败', error)
    message.error('创建扩图任务失败，' + (error as Error).message)
  }
}

/** 轮询定时器 */
let pollingTimer: ReturnType<typeof setInterval> | undefined
/** 连续查询失败次数：偶尔抖一次不该把整个任务丢掉 */
let pollErrorCount = 0

/**
 * 停止轮询并复位状态
 *
 * 任务成功、失败，或者组件被销毁时都要调用：不清定时器它会一直转下去。
 */
const clearPolling = () => {
  if (pollingTimer) {
    clearInterval(pollingTimer)
    pollingTimer = undefined
  }
  pollErrorCount = 0
  taskId.value = undefined
}

/**
 * 轮询任务状态
 *
 * 官方只提供「创建任务」和「查任务」两个接口，没有回调，所以只能前端定时查。
 * 3 秒一次：太快会白刷接口，太慢用户等得心焦。
 */
const startPolling = () => {
  if (!taskId.value) {
    return
  }
  pollingTimer = setInterval(async () => {
    const currentTaskId = taskId.value
    if (!currentTaskId) {
      clearPolling()
      return
    }
    try {
      const res = await getPictureOutPaintingTaskUsingGet({ taskId: currentTaskId })
      const taskResult = res.data.data?.output
      if (res.data.code !== 0 || !taskResult) {
        // 接口通了但拿不到任务信息（比如任务 id 已失效），再轮询也不会有结果
        message.error('查询任务失败，' + res.data.message)
        clearPolling()
        return
      }
      pollErrorCount = 0
      if (taskResult.taskStatus === 'SUCCEEDED') {
        message.success('扩图成功')
        resultImageUrl.value = taskResult.outputImageUrl
        clearPolling()
      } else if (taskResult.taskStatus === 'FAILED') {
        // 带上接口给的失败原因，用户才知道是图片不合适还是别的问题
        message.error('扩图失败，' + (taskResult.message || '请换张图片或稍后再试'))
        clearPolling()
      }
      // 其余状态（PENDING 排队中 / RUNNING 处理中 / SUSPENDED 挂起）等着下一次轮询
    } catch (error) {
      pollErrorCount += 1
      console.error('查询扩图任务失败', error)
      // 网络抖一下就放弃的话，用户会一直干等一个永远不会出现的提示，所以容忍连续几次失败
      if (pollErrorCount >= 3) {
        message.error('连续查询任务失败，请稍后重新生成')
        clearPolling()
      }
    }
  }, 3000)
}

// 组件销毁（离开页面、图片被换掉）时一定要停掉轮询，否则定时器会一直挂在那里
onUnmounted(clearPolling)

// 应用结果中（要把结果从临时地址转存到自己的对象存储）
const uploadLoading = ref(false)

/**
 * 应用结果：把扩图结果保存成平台的图片
 *
 * 阿里云给的结果地址是临时的，必须调 URL 上传接口转存到自己的对象存储，否则过一阵子就失效了。
 * 传了 picture.id 后端会覆盖原图（还是同一条图片记录），不传就是新建一张。
 */
const handleUpload = async () => {
  const fileUrl = resultImageUrl.value
  if (!fileUrl) {
    return
  }
  uploadLoading.value = true
  try {
    const params: API.PictureUploadRequest = { fileUrl }
    if (props.picture) {
      params.id = props.picture.id
    }
    // 空间 id 只在空间内编辑时才有值
    if (props.spaceId != null) {
      params.spaceId = props.spaceId
    }
    const res = await uploadPictureByUrlUsingPost(params)
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
      // 结果已经落到平台上了，留在弹窗里会和新原图重复，清掉
      resultImageUrl.value = undefined
      closeModal()
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch (error) {
    console.error('图片上传失败', error)
    message.error('图片上传失败，' + (error as Error).message)
  } finally {
    uploadLoading.value = false
  }
}

// 打开弹窗
const openModal = () => {
  visible.value = true
}

/**
 * 关闭弹窗
 *
 * 故意不打断正在跑的任务：轮询继续，回到这个弹窗结果还在，用户不用重新生成一次（一次调用是要花钱的）。
 * 真正的清理在 onUnmounted 里。
 */
const closeModal = () => {
  visible.value = false
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>

<style>
/* 这里不能用 scoped：类名加在 a-modal 上，弹窗内容被 teleport 到 body 里了 */
.image-out-painting {
  text-align: center;
}

/* 两张图限制一下尺寸：扩图前后尺寸不一样，不限制的话弹窗会被撑得很高 */
.image-out-painting .ant-row img {
  max-width: 100%;
  max-height: 320px;
  object-fit: contain;
}

.image-out-painting .image-out-painting-status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 200px;
  color: rgb(0 0 0 / 45%);
}
</style>
