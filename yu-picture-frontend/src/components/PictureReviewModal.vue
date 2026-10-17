<template>
  <a-modal
    v-model:open="visible"
    :title="isReject ? '审核拒绝' : '审核通过'"
    :confirm-loading="submitting"
    :ok-text="isReject ? '确认拒绝' : '确认通过'"
    :ok-button-props="{ danger: isReject }"
    cancel-text="取消"
    @ok="handleOk"
    @cancel="closeModal"
  >
    <!-- 拒绝是危险操作：模态框自带这条警告 + 危险色的确定按钮，
         本身就是一次「确认后才执行」，不再额外弹一层确认框 -->
    <a-alert
      v-if="isReject"
      type="warning"
      show-icon
      message="拒绝后该图片将从首页移除，用户无法再看到它"
      style="margin-bottom: 16px"
    />
    <a-form layout="vertical">
      <a-form-item label="审核原因">
        <a-textarea
          v-model:value="reviewMessage"
          :placeholder="
            isReject
              ? '可填写拒绝原因，留空默认为「管理员审核拒绝」'
              : '可填写通过说明，留空默认为「管理员操作通过」'
          "
          :auto-size="{ minRows: 3, maxRows: 5 }"
          :maxlength="200"
          show-count
          allow-clear
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { doPictureReviewUsingPost } from '@/api/pictureController.ts'
import { PIC_REVIEW_STATUS_ENUM } from '@/constants/picture.ts'

interface Props {
  /** 审核成功后的回调，父组件用它刷新列表 / 详情 */
  onSuccess?: () => void
}

const props = withDefaults(defineProps<Props>(), {})

// 是否可见
const visible = ref(false)
// 提交中：转圈期间禁用确定按钮，避免连点提交两次审核
const submitting = ref(false)
// 审核原因，每次打开都清空
const reviewMessage = ref('')

/**
 * 待审核图片的 id。
 * 原样保留后端返回的值：它是后端 Long 在 JSON 里的字符串形态（雪花 id 超过
 * Number.MAX_SAFE_INTEGER），一旦 Number() / parseInt() 就会丢精度、审到错误的图上。
 */
const pictureId = ref<API.Picture['id']>()
// 目标审核状态
const reviewStatus = ref<number>(PIC_REVIEW_STATUS_ENUM.PASS)

const isReject = computed(() => reviewStatus.value === PIC_REVIEW_STATUS_ENUM.REJECT)

/**
 * 实际提交的审核原因
 *
 * 留空时按审核结果补一句默认说明，让审核记录可读
 * （后端允许 reviewMessage 为空，这只是前端补的默认文案）
 */
const finalReviewMessage = computed(
  () =>
    reviewMessage.value.trim() || (isReject.value ? '管理员审核拒绝' : '管理员操作通过'),
)

/**
 * 打开模态框
 * @param picture 待审核的图片，只取其中的 id
 * @param status 审核结果：PASS 通过 / REJECT 拒绝
 */
const openModal = (picture: API.Picture | API.PictureVO, status: number) => {
  pictureId.value = picture?.id
  reviewStatus.value = status
  reviewMessage.value = ''
  visible.value = true
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false
}

// 确定：提交审核
const handleOk = async () => {
  const id = pictureId.value
  if (!id) {
    message.error('图片 id 不存在')
    return
  }
  submitting.value = true
  try {
    const res = await doPictureReviewUsingPost({
      id,
      reviewStatus: reviewStatus.value,
      reviewMessage: finalReviewMessage.value,
    })
    if (res.data.code === 0) {
      message.success(isReject.value ? '已拒绝' : '已通过')
      closeModal()
      props.onSuccess?.()
    } else {
      message.error('审核操作失败，' + res.data.message)
    }
  } catch (error) {
    message.error('审核操作失败，' + (error as Error).message)
  } finally {
    submitting.value = false
  }
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>
