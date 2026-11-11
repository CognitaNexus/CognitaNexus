<script setup lang="ts">
/**
 * 待办总览（FR14「统一入口」）。
 *
 * 管理员此前要在四处找待办：审核台（待治理成果、认领、门户修订）、治理台（重复候选、异常）、
 * 采集台（失败任务）、一致性页（投递积压）。这一页只回答一个问题：**现在有多少件需要人做决定的事**，
 * 以及点进去该去哪一页。
 *
 * 两条展示约定：
 *  1. 计数为 null 表示「当前账号没有这类审核能力」，显示「无权限查看」而不是 0。
 *     把「看不到」显示成「没有」会让人以为系统已经清空，这比不显示更危险；
 *  2. 每一张卡都写明点击后去的是哪一页、那一页能做什么。只给数字不给去处，
 *     待办就只是仪表盘上的装饰。
 */
import { computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import { adminApi } from '@/api'
import { useAsync } from '@/composables/useAsync'
import AsyncBlock from '@/components/AsyncBlock.vue'

const todos = useAsync<Record<string, unknown>>(() => adminApi.todos())

interface TodoCard {
  key: string
  label: string
  hint: string
  to: string
  action: string
  value: unknown
}

function countOf(key: string): unknown {
  return todos.data.value?.[key]
}

const cards = computed<TodoCard[]>(() => [
  {
    key: 'achievementsPending',
    label: '待治理确认的成果',
    hint: '确认后才进入公开检索；确认或撤回都必须写依据。',
    to: '/review',
    action: '去审核台',
    value: countOf('achievementsPending'),
  },
  {
    key: 'dedupCandidatesOpen',
    label: '未裁决的重复候选',
    hint: '只是候选，不会自动合并；判定前请核对两端证据与相似度。',
    to: '/governance',
    action: '去治理台',
    value: countOf('dedupCandidatesOpen'),
  },
  {
    key: 'anomaliesOpen',
    label: '未处理的治理异常',
    hint: '缺关键词、缺参与者、同名待消歧等；异常不等于数据错误。',
    to: '/governance',
    action: '去治理台',
    value: countOf('anomaliesOpen'),
  },
  {
    key: 'claimsPending',
    label: '待审核的认领申请',
    hint: '通过只授予该学者门户的维护权，不代表成果真实性背书。',
    to: '/review',
    action: '去审核台',
    value: countOf('claimsPending'),
  },
  {
    key: 'portalChangesPending',
    label: '待审的门户字段变更',
    hint: '待审版本不会覆盖已公开版本，公开页仍是旧值。',
    to: '/review',
    action: '去审核台',
    value: countOf('portalChangesPending'),
  },
])

function display(value: unknown): string {
  if (value === null || value === undefined) return '无权限查看'
  const num = Number(value)
  return Number.isFinite(num) ? num.toLocaleString() : String(value)
}

const total = computed(() => countOf('todoTotal'))
const note = computed(() => String(todos.data.value?.todoNote ?? ''))

/** 运行状态不是待办，但仍需要一眼看见：失败的采集任务不会自己消失。 */
const runtimeLinks = [
  { to: '/ingestion', label: '采集任务与失败重试', hint: '失败任务可从检查点重试，不必重建' },
  { to: '/ops', label: '投递积压与死信', hint: '死信可重投；投递未追平说明查询侧数据还停在旧版本' },
  { to: '/audit', label: '账号与审计', hint: '停用账号、授予或撤销角色，并查看操作留痕' },
]

onMounted(todos.run)
</script>

<template>
  <div>
    <h1>待办</h1>
    <p class="small faint" style="margin-top: 0">
      只统计需要人做决定的事项。运行状态（采集失败、投递积压）在各自页面观察，不计入下面的合计。
    </p>

    <AsyncBlock
      :loading="todos.loading.value"
      :error="todos.error.value"
      :error-code="todos.errorCode.value"
      :empty="false"
    >
      <div class="panel">
        <div class="panel-head">
          <h2>合计 {{ display(total) }} 件</h2>
          <span class="tiny faint">按当前账号的审核能力裁剪</span>
        </div>
        <div class="panel-body tight">
          <div class="grid cols-2">
            <RouterLink
              v-for="card in cards"
              :key="card.key"
              :to="card.to"
              class="result-item"
              style="display: block; color: inherit; text-decoration: none"
            >
              <div class="result-title" style="display: flex; justify-content: space-between; gap: 10px">
                <span>{{ card.label }}</span>
                <span class="mono" style="font-size: 20px">{{ display(card.value) }}</span>
              </div>
              <div class="tiny faint" style="margin-top: 4px">{{ card.hint }}</div>
              <div class="tiny" style="margin-top: 6px">{{ card.action }} →</div>
            </RouterLink>
          </div>
          <p v-if="note" class="tiny faint" style="margin: 12px 0 0">{{ note }}</p>
        </div>
      </div>

      <div class="panel">
        <div class="panel-head"><h2>运行状态（不计入待办）</h2></div>
        <div class="panel-body tight">
          <ul style="margin: 0; padding-left: 18px">
            <li v-for="link in runtimeLinks" :key="link.to" style="margin: 6px 0">
              <RouterLink :to="link.to">{{ link.label }}</RouterLink>
              <span class="tiny faint"> — {{ link.hint }}</span>
            </li>
          </ul>
        </div>
      </div>
    </AsyncBlock>
  </div>
</template>
