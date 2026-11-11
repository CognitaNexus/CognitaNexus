<script setup lang="ts">
/**
 * 检索输入框 + 建议下拉。
 *
 * 三条约束（都是踩过一次才知道要写的）：
 *
 * 1. **建议失败不打断输入**：接口报错时静默收起下拉，用户照常回车检索。建议是辅助，不是前置条件；
 *    在输入框下方弹一条红色错误，只会让正在打字的人以为自己不能检索。
 * 2. **请求竞态**：连续敲字时慢响应会盖掉新结果。用递增序号丢弃旧响应，与检索页同一套做法。
 * 3. **中文输入法**：选字时按回车会触发 keydown，若不判 isComposing，用户每选一次字就被跳转一次。
 *
 * 下拉内容只来自服务端，前端不做二次过滤：能出现在这里的前提是「公开生效」，那条规则由服务端守。
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { queryApi } from '@/api'
import type { SearchSuggestions } from '@/api/types'

const props = withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    ariaLabel?: string
    dataNature?: string
    limit?: number
    /**
     * 只渲染输入框，不渲染「检索」按钮。
     *
     * 检索页自带一个主按钮且外层已经是 form，组件再塞一个按钮会出现两个提交入口，
     * 而内层 form 会在某些浏览器里与外层嵌套，Enter 键的行为随浏览器变化。
     */
    hideButton?: boolean
  }>(),
  { placeholder: '', ariaLabel: '检索关键词', limit: 6, hideButton: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [keyword: string]
  pick: [payload: { kind: 'achievement' | 'scholar'; id: string }]
}>()

const TYPE_LABELS: Record<string, string> = {
  PAPER: '论文',
  PATENT: '专利',
  PROJECT: '项目',
  AWARD: '获奖',
}

interface Suggestion {
  kind: 'achievement' | 'scholar'
  id: string
  label: string
  meta: string
}

const open = ref(false)
const active = ref(-1)
const data = ref<SearchSuggestions | null>(null)
const loading = ref(false)

let debounceTimer: number | undefined
let seq = 0

const items = computed<Suggestion[]>(() => {
  const current = data.value
  if (!current) {
    return []
  }
  return [
    ...current.achievements.map((item) => ({
      kind: 'achievement' as const,
      id: item.id,
      label: item.title,
      meta: [item.publicationYear ?? '年份未知', TYPE_LABELS[item.type] ?? item.type].join(' · '),
    })),
    ...current.scholars.map((item) => ({
      kind: 'scholar' as const,
      id: item.scholarId,
      label: item.scholarName,
      meta: `学者 · 公开成果 ${item.qualifiedAchievementCount} 篇`,
    })),
  ]
})

const achievementCount = computed(() => data.value?.achievements.length ?? 0)

watch(
  () => props.modelValue,
  (value) => {
    const keyword = value.trim()
    window.clearTimeout(debounceTimer)
    if (!keyword) {
      seq += 1
      data.value = null
      open.value = false
      active.value = -1
      loading.value = false
      return
    }
    debounceTimer = window.setTimeout(() => load(keyword), 220)
  },
)

onBeforeUnmount(() => window.clearTimeout(debounceTimer))

async function load(keyword: string) {
  const mine = ++seq
  loading.value = true
  try {
    const result = await queryApi.suggest(keyword, props.dataNature, props.limit)
    if (mine !== seq) {
      return
    }
    data.value = result
    active.value = -1
    open.value = items.value.length > 0
  } catch {
    if (mine !== seq) {
      return
    }
    // 建议失败当作「没有建议」，不抛给用户看。
    data.value = null
    open.value = false
  } finally {
    if (mine === seq) {
      loading.value = false
    }
  }
}

function onInput(event: Event) {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}

function onSubmit(event?: KeyboardEvent) {
  // 中文输入法选字时的回车不是「提交」。
  if (event && (event.isComposing || event.keyCode === 229)) {
    return
  }
  // 已经用方向键选中某条建议时，回车就是「选它」。
  // 否则方向键只把高亮移来移去、回车却按关键词检索，键盘操作等于白做——这处是浏览器断言抓到的。
  if (open.value && active.value >= 0) {
    choose(active.value)
    return
  }
  close()
  emit('submit', props.modelValue.trim())
}

function move(step: number) {
  if (!open.value || items.value.length === 0) {
    return
  }
  const next = active.value + step
  active.value = (next + items.value.length) % items.value.length
}

function choose(index: number) {
  const item = items.value[index]
  if (!item) {
    onSubmit()
    return
  }
  close()
  emit('pick', { kind: item.kind, id: item.id })
}

function close() {
  open.value = false
  active.value = -1
}

function onBlur() {
  // 延迟关闭给点击留出时间：直接关会让 mousedown 落在已移除的节点上，点击落空。
  window.setTimeout(close, 120)
}

function reopen() {
  if (items.value.length > 0) {
    open.value = true
  }
}

// 为什么还要绑一次 click：按 Esc 收起后输入框仍然是焦点元素，再点它**不会再触发 focus**，
// 于是「收起后想再看一眼建议」只能靠改一个字。这不是理论问题，浏览器断言里第一次就卡在这里。
</script>

<template>
  <div class="quick-search suggest" :class="{ 'no-button': hideButton }">
    <input
      :value="modelValue"
      type="search"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      role="combobox"
      aria-autocomplete="list"
      :aria-expanded="open ? 'true' : 'false'"
      aria-controls="suggest-list"
      :aria-activedescendant="active >= 0 ? `suggest-item-${active}` : undefined"
      autocomplete="off"
      @input="onInput"
      @keydown.down.prevent="move(1)"
      @keydown.up.prevent="move(-1)"
      @keydown.enter.prevent="onSubmit"
      @keydown.esc.prevent="close"
      @focus="reopen"
      @click="reopen"
      @blur="onBlur"
    />
    <button v-if="!hideButton" type="button" @click="onSubmit()">检索</button>

    <ul v-if="open" id="suggest-list" class="suggest-list" role="listbox">
      <li v-if="loading && items.length === 0" class="suggest-hint">正在检索建议…</li>
      <template v-else>
        <li
          v-for="(item, index) in items"
          :id="`suggest-item-${index}`"
          :key="`${item.kind}-${item.id}`"
          class="suggest-item"
          :class="{ active: index === active, scholar: item.kind === 'scholar' }"
          role="option"
          :aria-selected="index === active ? 'true' : 'false'"
          @mousedown.prevent="choose(index)"
          @mouseenter="active = index"
        >
          <span class="suggest-label">{{ item.label }}</span>
          <span class="suggest-meta">{{ item.meta }}</span>
        </li>
        <li v-if="items.length > 0" class="suggest-hint">
          <template v-if="achievementCount > 0">
            正在检索题名；正式检索另外命中摘要、作者名与 DOI
          </template>
          <template v-else>回车检索全部结果</template>
        </li>
      </template>
    </ul>
  </div>
</template>

<style scoped>
.suggest {
  position: relative;
}

.suggest input {
  flex: 1 1 auto;
}

/* 没有按钮时输入框要自成一体：全局 .quick-search input 为了与按钮拼接去掉了右边框，
   少了按钮就缺一条边。高度对齐检索页的筛选控件（34px）。 */
.suggest.no-button input {
  border-right: 1px solid var(--rule-strong);
  border-radius: var(--radius);
  height: 34px;
}

.suggest-list {
  position: absolute;
  z-index: 40;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  margin: 0;
  padding: 4px 0;
  list-style: none;
  background: var(--panel);
  border: 1px solid var(--rule-strong);
  border-radius: var(--radius);
  box-shadow: 0 6px 18px rgba(22, 25, 29, 0.12);
  max-height: 320px;
  overflow-y: auto;
}

.suggest-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
  cursor: pointer;
  border-left: 2px solid transparent;
}

.suggest-item.active {
  background: var(--accent-soft);
  border-left-color: var(--accent);
}

.suggest-label {
  font-family: var(--serif, Georgia, 'Songti SC', serif);
  font-size: 13px;
  color: var(--text);
  line-height: 1.4;
  /* 长题名截断到一行：建议的用途是「认出来」，不是「读完」。 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.suggest-item.scholar .suggest-label {
  font-family: inherit;
}

.suggest-meta {
  font-size: 11.5px;
  color: var(--text-faint);
}

.suggest-hint {
  padding: 5px 10px 3px;
  font-size: 11.5px;
  color: var(--text-faint);
  border-top: 1px solid var(--rule);
  margin-top: 3px;
}
</style>
