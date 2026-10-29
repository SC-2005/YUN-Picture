<template>
  <a-modal
    class="image-cropper"
    v-model:open="visible"
    title="编辑图片"
    :footer="false"
    @cancel="closeModal"
  >
    <!-- 比例选择：自由比例 / 常用固定比例 -->
    <div class="aspect-ratio-selector">
      <a-radio-group v-model:value="aspectRatio" button-style="solid">
        <a-radio-button v-for="item in ASPECT_RATIO_OPTIONS" :key="item.value" :value="item.value">
          {{ item.label }}
        </a-radio-button>
      </a-radio-group>
    </div>
    <!-- 图片读取失败：常见原因是对象存储没配 CORS、或 dev server 没起代理 -->
    <a-result v-if="imageError" status="warning" title="图片加载失败" :sub-title="imageError">
      <template #extra>
        <a-button @click="loadImage()">重试</a-button>
      </template>
    </a-result>
    <!-- 图片还没读成同源数据：先占位，避免把跨域地址喂给编辑器、点确认时导不出 -->
    <div v-else-if="!imageSource" class="image-cropper-status">
      <a-spin v-if="loadingImage" />
      <span>{{ loadingImage ? '图片加载中…' : '暂无图片' }}</span>
    </div>
    <template v-else>
      <!-- 裁剪区 -->
      <vue-cropper
        ref="cropperRef"
        :img="imageSource"
        :auto-crop="true"
        :fixed="aspectRatio !== 'free'"
        :fixed-number="fixedNumber"
        :fixed-box="false"
        :center-box="true"
        :can-move-box="true"
        :can-move="true"
        :can-scale="true"
        :info="true"
        output-type="png"
        @img-load="handleImgLoad"
      />
      <div style="margin-bottom: 16px" />
      <!-- 图片操作 -->
      <div class="image-cropper-actions">
        <a-space wrap>
          <a-button :disabled="disabled" @click="rotateLeft">向左旋转 90°</a-button>
          <a-button :disabled="disabled" @click="rotateRight">向右旋转 90°</a-button>
          <a-button :disabled="disabled" @click="changeScale(1)">放大</a-button>
          <a-button :disabled="disabled" @click="changeScale(-1)">缩小</a-button>
          <a-button type="primary" :loading="loading" :disabled="disabled" @click="handleConfirm">
            确认
          </a-button>
        </a-space>
        <!-- 缩放：和上面的放大/缩小按钮走同一套逻辑，只是换成滑动条，方便连续微调 -->
        <div class="image-cropper-tool">
          <span class="tool-label">缩放</span>
          <a-slider
            v-model:value="scaleValue"
            :min="20"
            :max="300"
            :step="1"
            :disabled="disabled"
            :tip-formatter="(value?: number) => `${value}%`"
            @change="handleScaleChange"
          />
        </div>
        <!-- 任意角度旋转：vue-cropper 只支持 90° 的整数倍，这里用 canvas 先把原图转好再交给它 -->
        <div class="image-cropper-tool">
          <span class="tool-label">任意角度</span>
          <a-input-number
            v-model:value="rotateDegree"
            :min="0"
            :max="359"
            :precision="0"
            :disabled="disabled"
            addon-after="°"
            style="width: 140px"
          />
          <a-button :disabled="disabled" :loading="rotating" @click="handleRotate">
            顺时针旋转
          </a-button>
          <a-typography-text type="secondary">
            （可点多次累加，例如 45° + 45° = 90°）
          </a-typography-text>
        </div>
      </div>
    </template>
  </a-modal>
</template>

<script lang="ts" setup>
import { computed, nextTick, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { uploadPictureUsingPost } from '@/api/pictureController.ts'
import { fetchImageAsDataUrl, rotateImage } from '@/utils/image.ts'

import type { VueCropperInstance } from '@/types/vue-cropper'

interface Props {
  /** 要编辑的图片地址（对象存储的绝对地址或站内相对地址都行） */
  imageUrl?: string
  /** 当前图片信息：传了 id 就是「改这张图」，不传就是新建 */
  picture?: API.PictureVO
  /** 要保存到的空间 id，不传表示公共图库 */
  spaceId?: number
  onSuccess?: (newPicture: API.PictureVO) => void
}

const props = defineProps<Props>()

/** 裁剪框比例选项 */
const ASPECT_RATIO_OPTIONS = [
  { label: '自由比例', value: 'free' },
  { label: '1:1', value: '1:1' },
  { label: '4:3', value: '4:3' },
  { label: '16:9', value: '16:9' },
  { label: '3:4', value: '3:4' },
  { label: '9:16', value: '9:16' },
]

/** 编辑器组件的引用，vue-cropper 是选项式组件，内部方法只能通过 ref 调用 */
const cropperRef = ref<VueCropperInstance>()

// 是否可见
const visible = ref(false)

// 图片是否正在读取（读 base64 是异步的）
const loadingImage = ref(false)
// 图片读取失败的原因
const imageError = ref('')

/**
 * 交给 vue-cropper 的图片数据（同源 base64）
 *
 * 为什么不直接把 props.imageUrl 塞进去：vue-cropper 会自己 new Image() 并设置 crossOrigin，
 * 还会额外发一次 XHR 读 EXIF。图片存在跨域的对象存储上时，这两步都会被浏览器拦下来
 * （画布被污染 → 点确认时 getCropBlob 直接报错）。所以先在这里把图片读成同源的 base64。
 * 详见 @/utils/image.ts 的说明。
 */
const imageSource = ref('')

/** 图片还没准备好时，操作按钮全部禁用 */
const disabled = computed(() => !imageSource.value)

/**
 * 缩放滑动条上的百分比
 *
 * 100% 指「刚打开时图片适应编辑区的大小」（vue-cropper 初始化时自己算的），不是图片的原始像素。
 * 这样用户看到 200% 就知道「比刚才大一倍」，符合直觉。换算逻辑见下面「缩放滑动条」一节。
 */
const scaleValue = ref(100)
/** 刚加载完时的缩放倍率，用来把 vue-cropper 的倍率换算成滑动条的百分比；0 表示还没拿到 */
const baseScale = ref(0)

/**
 * 读取原图
 *
 * 弹窗每次打开都会调用一次，读完直接覆盖 imageSource —— 不做「地址没变就跳过」的缓存：
 * 上一次编辑可能转过角度（imageSource 已经不是原图了），缓存下来会让下次打开带着上次的结果。
 * 重新读一遍还能顺带把上一次占着的大字符串释放掉。
 */
const loadImage = async () => {
  const url = props.imageUrl
  if (!url) {
    imageSource.value = ''
    return
  }
  loadingImage.value = true
  imageError.value = ''
  // 先清空再读：加载失败时不会残留上一张图
  imageSource.value = ''
  // 缩放基准也跟着清掉：换了图片，vue-cropper 会重新算一遍「适应编辑区」的倍率，
  // 留着上一次的基准会让滑动条在新图加载完之前先跳到一个莫名其妙的值
  baseScale.value = 0
  scaleValue.value = 100
  try {
    imageSource.value = await fetchImageAsDataUrl(url)
  } catch (error) {
    console.error('图片加载失败', error)
    imageError.value = (error as Error).message || '请检查图片地址是否可访问'
  } finally {
    loadingImage.value = false
  }
}

// --------- 裁剪比例 ---------
const aspectRatio = ref('free')

/** 把 '16:9' 这样的比例字符串转成 vue-cropper 需要的 [宽, 高]；自由比例给 [0, 0] 表示不限制 */
const fixedNumber = computed<number[]>(() => {
  if (aspectRatio.value === 'free') {
    return [0, 0]
  }
  const [width, height] = aspectRatio.value.split(':').map(Number)
  return [width, height]
})

/**
 * 切换比例后，重新生成一个符合新比例的裁切框
 *
 * 为什么要手动调一次 goAutoCrop：vue-cropper 只在「拖拽裁切框」和「换图片 / 旋转」时
 * 才会按 fixedNumber 重算尺寸，光改 fixedNumber 它不管 —— 不补这一步的话，
 * 用户点了「1:1」会看到裁切框还是原来的（比如 16:9）样子，导出也还是旧比例。
 *
 * 切回「自由比例」时不用重算：裁切框保持原样，用户接下来自己拖就是自由尺寸。
 */
watch(aspectRatio, async () => {
  if (aspectRatio.value === 'free') {
    return
  }
  await nextTick()
  cropperRef.value?.goAutoCrop()
})

// --------- 旋转 / 缩放 ---------

// 向左旋转 90°
const rotateLeft = () => {
  cropperRef.value?.rotateLeft()
}

// 向右旋转 90°
const rotateRight = () => {
  cropperRef.value?.rotateRight()
}

// 缩放：正数放大、负数缩小
const changeScale = (num: number) => {
  cropperRef.value?.changeScale(num)
  syncScaleValue()
}

// --------- 缩放滑动条 ---------

/** 把 vue-cropper 当前的缩放倍率同步到滑动条 */
const syncScaleValue = () => {
  const scale = cropperRef.value?.scale
  if (!scale || !baseScale.value) {
    return
  }
  scaleValue.value = Math.round((scale / baseScale.value) * 100)
}

/**
 * 图片加载完成
 *
 * vue-cropper 加载完会按容器尺寸先把图片缩一次，这时才能拿到「适应编辑区」的基准倍率，
 * 所以要在它读完（img-load 事件）之后再记，顺便把滑动条归到 100%。
 * 之后每次换图（包括手工任意角度旋转）它都会重新抛一次，正好复用同一段逻辑。
 *
 * @param result vue-cropper 抛出的结果：'success' / 'error'，图片为空时是一个 Error
 */
const handleImgLoad = (result: 'success' | 'error' | Error) => {
  if (result === 'error') {
    // 图片读进来了但解不开（文件本身损坏之类），给个提示，别让用户对着空白编辑区发呆
    imageError.value = '图片无法解析，请换一张图片'
    imageSource.value = ''
    return
  }
  if (result !== 'success') {
    return
  }
  baseScale.value = cropperRef.value?.scale || 1
  scaleValue.value = 100
}

// 滚轮 / 双指缩放不经过上面的按钮和滑动条，直接改组件内部的倍率，这里跟着同步一下
watch(
  () => cropperRef.value?.scale,
  () => syncScaleValue(),
)

/**
 * 拖拽缩放滑动条
 *
 * vue-cropper 的 changeScale 收的是「步数」而不是倍率，每步的实际倍率是 20 / 图片最长边
 * （它源码里写死的常量）。所以这里先按这个公式把百分比换算成倍率，再把倍率差换算成步数 ——
 * 一格子一下地硬加是不对的：同一格在不同尺寸的图片上放大倍率差得很远。
 */
const handleScaleChange = (value: number | [number, number]) => {
  const cropper = cropperRef.value
  const percent = Array.isArray(value) ? value[0] : value
  if (!cropper || !baseScale.value) {
    return
  }
  const target = baseScale.value * (percent / 100)
  const current = cropper.scale
  if (Math.abs(target - current) < 1e-4) {
    return
  }
  const step = 20 / Math.max(cropper.trueWidth, cropper.trueHeight)
  if (!Number.isFinite(step) || step <= 0) {
    return
  }
  cropper.changeScale((target - current) / step)
  // 回读真实倍率：缩到盖不住裁切框时 changeScale 会把缩放挡下来，滑动条要跟着回弹，
  // 不然滑动条显示的百分比和图片实际大小就永久对不上了。
  syncScaleValue()
}

// 任意角度旋转
const rotateDegree = ref(45)
/** 旋转处理中（canvas 重绘 + 重新编码） */
const rotating = ref(false)

/**
 * 按输入的角度顺时针旋转
 *
 * 每次都基于「当前已经旋转过的图」再转，所以可以点多次累加。
 * 转完把旋转结果当新图片交给 vue-cropper：它内部的 rotate 计数自然归零，坐标依旧准确，
 * 缩放也会重新按新图算一遍（handleImgLoad 里会把滑动条归位）。
 */
const handleRotate = async () => {
  const degree = Number(rotateDegree.value)
  if (!imageSource.value || !degree) {
    return
  }
  rotating.value = true
  try {
    imageSource.value = await rotateImage(imageSource.value, degree)
    message.success(`已顺时针旋转 ${degree}°`)
  } catch (error) {
    console.error('图片旋转失败', error)
    message.error((error as Error).message || '图片旋转失败')
  } finally {
    rotating.value = false
  }
}

// --------- 确认上传 ---------
const loading = ref(false)

// 确认裁剪
const handleConfirm = () => {
  cropperRef.value?.getCropBlob((blob: Blob) => {
    // blob 是裁切 + 旋转后的文件，转成 File 之后就能复用图片上传接口
    const fileName = (props.picture?.name || 'image') + '.png'
    const file = new File([blob], fileName, { type: blob.type })
    handleUpload({ file })
  })
}

/**
 * 上传图片
 *
 * 传了 picture.id 时后端会覆盖原图（文件换新、记录还是同一条），
 * 不传就是新建一张图，两种情况的接口是同一个。
 */
const handleUpload = async ({ file }: { file: File }) => {
  loading.value = true
  try {
    const params: API.uploadPictureUsingPOSTParams = props.picture ? { id: props.picture.id } : {}
    // 空间 id 只在空间内编辑时才有值
    if (props.spaceId != null) {
      params.spaceId = props.spaceId
    }
    const res = await uploadPictureUsingPost(params, {}, file)
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
      closeModal()
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch (error) {
    console.error('图片上传失败', error)
    message.error('图片上传失败，' + (error as Error).message)
  } finally {
    loading.value = false
  }
}

// 打开弹窗
const openModal = async () => {
  visible.value = true
  // 打开才去读图片：读的是原图、还要转成 base64，图片大的时候开销不小，
  // 用户不点「编辑图片」就白白读一遍（组件是随页面挂载的，不点也会执行）。
  await loadImage()
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false
  // 本次编辑到此为止，把图片数据放掉，下次打开重新从原图开始
  imageSource.value = ''
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>

<style>
/* 这里不能用 scoped：类名加在 a-modal 上，弹窗内容被 teleport 到 body 里了 */
.image-cropper {
  text-align: center;
}

.image-cropper .vue-cropper {
  height: 400px !important;
}

.image-cropper .aspect-ratio-selector {
  margin-bottom: 16px;
}

.image-cropper .image-cropper-status {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 400px;
  color: rgb(0 0 0 / 45%);
}

.image-cropper .image-cropper-tool {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

/* 缩放滑动条给个固定宽度，否则整行会被拉伸得很长 */
.image-cropper .image-cropper-tool .ant-slider {
  width: 200px;
  margin: 0;
}

/* 标签和附注都不换行：弹窗在窄屏下只有 500 多像素，「任意角度」被折成两行会很难看 */
.image-cropper .image-cropper-tool .tool-label {
  color: rgb(0 0 0 / 65%);
  white-space: nowrap;
}

.image-cropper .image-cropper-tool .ant-typography {
  white-space: nowrap;
}
</style>
