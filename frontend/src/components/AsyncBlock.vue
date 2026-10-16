<script setup lang="ts">
/**
 * 加载 / 失败 / 空 三态的统一呈现。
 *
 * 关键约定：**失败绝不渲染成空**。列表为空和请求失败在界面上长得像，但含义完全相反——前者
 * 是「确实没有」，后者是「不知道有没有」。把失败显示成「暂无数据」，用户会以为平台里真的
 * 没有这些成果，从而得出错误结论。因此三种状态各有各的文案与颜色。
 *
 * 三态都放在一个 aria-live 容器里播报：这里的读取是用户点了按钮或切了口径触发的，
 * 结果必须能被屏幕阅读器听见，否则键盘/读屏用户按下去之后得不到任何反馈。用 polite
 * 而非 assertive——它不该打断用户正在读的内容。
 */
defineProps<{
  loading: boolean
  error: string
  errorCode?: string
  empty?: boolean
  emptyText?: string
}>()
</script>

<template>
  <div aria-live="polite" :aria-busy="loading">
    <div v-if="loading" class="loading"><span class="spinner"></span> 正在读取…</div>

    <div v-else-if="error" class="notice danger" style="margin: 16px">
      <div><strong>读取失败：</strong>{{ error }}</div>
      <div v-if="errorCode" class="tiny mono" style="margin-top: 6px">错误码 {{ errorCode }}</div>
      <div class="tiny" style="margin-top: 6px">
        这条状态表示「尚未取到」，不表示「没有数据」。请稍后重试或检查对应服务是否已启动。
      </div>
    </div>

    <div v-else-if="empty" class="empty">{{ emptyText || '没有符合条件的记录。' }}</div>

    <slot v-else />
  </div>
</template>
