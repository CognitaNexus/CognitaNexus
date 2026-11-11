<script setup lang="ts">
/**
 * 我的成果提交。
 *
 * 三处刻意的设计：
 *
 * 1. **页面上把「提交不等于公开」写在表单上面**，而不是藏在帮助文档里。作者填完点提交，
 *    最容易的误解是「已经发布了」；等他第二天搜不到，就会认为平台吞了数据。
 * 2. **类型只有「论文」**。专利/项目/获奖各自要求申请号、项目编号这类必填事实，缺了它们
 *    建出来的记录在数据库层面会被类型一致性约束拒绝；与其让用户填完再失败，不如先说清楚。
 * 3. **查重提示照原样展示**，不改写成「重复」。相似题名不等于重复（合著、预印本、译文都可能是
 *    两条真实记录），最终由审核判断；界面先替它下结论，作者只会去改题名绕开检测。
 */
import { computed, onMounted, ref } from 'vue'
import {
  accountApi,
  type SubmissionImportResult,
  type SubmissionView,
} from '@/api'
import { describeError } from '@/composables/useAsync'

const form = ref({
  title: '',
  doi: '',
  venue: '',
  year: '' as string,
  authorsRaw: '',
  abstractText: '',
  scholarId: '',
})
const portals = ref<{ scholarId: string; displayName?: string | null }[]>([])
const items = ref<SubmissionView[]>([])
const note = ref('')
const error = ref('')
const busy = ref('')
const loading = ref(false)

/** 幂等键：一次填写生成一次，重试复用；提交成功后清空，下一次提交是新的一条。 */
let idempotencyKey = ''

const canSubmit = computed(() => form.value.title.trim().length > 0 && busy.value === '')

function ensureKey(): string {
  if (!idempotencyKey) {
    idempotencyKey =
      'sub-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
  }
  return idempotencyKey
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const mine = await accountApi.mySubmissions(50)
    items.value = mine.items ?? []
    note.value = mine.note ?? ''
    // 门户清单决定能不能顺手把成果挂到自己名下；拿不到就只是这一个下拉为空，
    // 不影响提交本身，因此这里单独兜住错误而不是让整页失败。
    try {
      const rows = await accountApi.myPortals()
      portals.value = (rows as unknown as { scholarId: string; displayName?: string }[]) ?? []
    } catch {
      portals.value = []
    }
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value) {
    return
  }
  busy.value = 'submit'
  error.value = ''
  try {
    const year = form.value.year.trim() ? Number(form.value.year.trim()) : null
    if (year !== null && !Number.isFinite(year)) {
      error.value = '发表年份需要是四位数字'
      return
    }
    const created = await accountApi.submitAchievement(
      {
        type: 'PAPER',
        title: form.value.title.trim(),
        doi: form.value.doi.trim() || undefined,
        venue: form.value.venue.trim() || undefined,
        publicationYear: year,
        authorsRaw: form.value.authorsRaw.trim() || undefined,
        abstractText: form.value.abstractText.trim() || undefined,
        scholarId: form.value.scholarId || undefined,
      },
      ensureKey(),
    )
    // 提交成功后换新键：下一次点提交是另一条记录，不能被上一次的幂等结果覆盖。
    idempotencyKey = ''
    items.value = [created, ...items.value]
    form.value = {
      title: '',
      doi: '',
      venue: '',
      year: '',
      authorsRaw: '',
      abstractText: '',
      scholarId: form.value.scholarId,
    }
    note.value = created.duplicateOf
      ? '已提交，待审核。注意：平台检测到疑似重复记录，审核时会一并核对。'
      : '已提交，待审核。审核通过后才会进入公开目录。'
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

async function withdraw(id: string) {
  busy.value = id
  error.value = ''
  try {
    const updated = await accountApi.withdrawSubmission(id)
    items.value = items.value.map((row) => (row.id === id ? updated : row))
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

const STATUS_CLASS: Record<string, string> = {
  SUBMITTED: 'neutral',
  NEED_MORE_INFO: 'warn',
  APPROVED: 'ok',
  REJECTED: 'danger',
  WITHDRAWN: 'neutral',
}

// ---------------------------------------------------------------------------
// 批量导入（BibTeX / RIS）
// ---------------------------------------------------------------------------
/** 2 MiB 与后端一致。前端先挡一次，避免把一个 10 MB 的 .bib 发出去再被拒。 */
const MAX_IMPORT_BYTES = 2 * 1024 * 1024
const importText = ref('')
const importFormat = ref<'AUTO' | 'BIBTEX' | 'RIS'>('AUTO')
const importFileName = ref('')
const importPreview = ref<SubmissionImportResult | null>(null)
const importApplied = ref<SubmissionImportResult | null>(null)
const importBusy = ref('')
const importError = ref('')

const canPreview = computed(
  () => importText.value.trim().length > 0 && importBusy.value === '',
)

function resetImportResults() {
  importPreview.value = null
  importApplied.value = null
  importError.value = ''
}

function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  resetImportResults()
  importFileName.value = file.name
  if (file.size > MAX_IMPORT_BYTES) {
    importError.value = '文件超过 2 MiB，请拆分后再导入'
    return
  }
  // 按 UTF-8 读；.bib 通常是 UTF-8。读到乱码时用户能立刻在右侧文本框里看出来，
  // 比后端猜编码再猜错要好。
  const reader = new FileReader()
  reader.onload = () => {
    importText.value = String(reader.result ?? '')
  }
  reader.onerror = () => {
    importError.value = '文件读取失败'
  }
  reader.readAsText(file, 'utf-8')
}

async function previewImport() {
  if (!canPreview.value) {
    return
  }
  importBusy.value = 'preview'
  resetImportResults()
  try {
    importPreview.value = await accountApi.importSubmissions({
      format: importFormat.value,
      content: importText.value,
      scholarId: form.value.scholarId || undefined,
      dryRun: true,
    })
    if (importPreview.value.parsed === 0) {
      importError.value = importPreview.value.note || '没有识别到任何条目'
    }
  } catch (cause) {
    importError.value = describeError(cause).message
  } finally {
    importBusy.value = ''
  }
}

async function confirmImport() {
  if (!importPreview.value || importBusy.value !== '') {
    return
  }
  importBusy.value = 'apply'
  try {
    importApplied.value = await accountApi.importSubmissions({
      format: importFormat.value,
      content: importText.value,
      scholarId: form.value.scholarId || undefined,
      dryRun: false,
    })
    importPreview.value = null
    // 导入成功后刷新「我的提交」：用户要马上看到刚才那批进了队列。
    await load()
  } catch (cause) {
    importError.value = describeError(cause).message
  } finally {
    importBusy.value = ''
  }
}

const IMPORT_STATUS_LABEL: Record<string, string> = {
  PREVIEW: '待导入',
  CREATED: '已提交',
  DUPLICATE_SUSPECTED: '已提交（疑似重复）',
  SKIPPED: '已跳过',
  FAILED: '失败',
}

const IMPORT_STATUS_CLASS: Record<string, string> = {
  PREVIEW: 'neutral',
  CREATED: 'ok',
  DUPLICATE_SUSPECTED: 'warn',
  SKIPPED: 'neutral',
  FAILED: 'danger',
}

onMounted(load)
</script>

<template>
  <div>
    <h1>提交成果</h1>
    <p class="dim">
      自助提交的成果需要审核。审核通过前，它<strong>不是成果</strong>：不进入公开目录、不产生参与关系、
      检索里也搜不到。
    </p>

    <div class="panel">
      <div class="panel-head"><h2>新建提交</h2></div>
      <div class="panel-body">
        <div class="grid cols-2">
          <div>
            <label class="tiny faint" for="sub-title">题名（必填）</label>
            <input id="sub-title" v-model="form.title" maxlength="500" style="width: 100%" />
            <div class="tiny faint">{{ form.title.length }} / 500</div>

            <label class="tiny faint" for="sub-venue" style="margin-top: 10px; display: block">
              发表载体（期刊/会议）
            </label>
            <input id="sub-venue" v-model="form.venue" maxlength="300" style="width: 100%" />

            <label class="tiny faint" for="sub-year" style="margin-top: 10px; display: block">
              发表年份
            </label>
            <input id="sub-year" v-model="form.year" inputmode="numeric" maxlength="4" style="width: 120px" />
            <span class="tiny faint">留空表示年份未知，不折算成任何一年</span>
          </div>

          <div>
            <label class="tiny faint" for="sub-doi">DOI</label>
            <input id="sub-doi" v-model="form.doi" maxlength="200" style="width: 100%" />

            <label class="tiny faint" for="sub-authors" style="margin-top: 10px; display: block">
              作者署名（原样填写，平台不解析作者序）
            </label>
            <input id="sub-authors" v-model="form.authorsRaw" maxlength="1000" style="width: 100%" />

            <label class="tiny faint" for="sub-scholar" style="margin-top: 10px; display: block">
              关联到我管理的门户（可选）
            </label>
            <select id="sub-scholar" v-model="form.scholarId" style="width: 100%">
              <option value="">不关联（只建成果）</option>
              <option v-for="portal in portals" :key="portal.scholarId" :value="portal.scholarId">
                {{ portal.displayName || portal.scholarId }}
              </option>
            </select>
            <div class="tiny faint">
              关联后在批准时建立「本人确认」的参与关系；不关联则只建成果，不替你认下署名。
            </div>
          </div>
        </div>

        <label class="tiny faint" for="sub-abstract" style="margin-top: 10px; display: block">摘要</label>
        <textarea id="sub-abstract" v-model="form.abstractText" rows="4" maxlength="5000" style="width: 100%" />

        <div class="btn-row" style="margin-top: 10px">
          <button class="btn primary" type="button" :disabled="!canSubmit" @click="submit">
            {{ busy === 'submit' ? '提交中…' : '提交待审' }}
          </button>
          <span class="tiny faint">目前只接受论文类型；专利/项目/获奖请在治理端录入</span>
        </div>

        <p v-if="error" class="notice danger" style="margin-top: 12px">{{ error }}</p>
        <p v-if="note" class="notice info" style="margin-top: 12px">{{ note }}</p>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>从文件批量导入</h2>
        <span class="tiny faint">BibTeX（.bib）/ RIS（.ris），纯本地解析，不联网</span>
      </div>
      <div class="panel-body">
        <p class="tiny faint" style="margin-top: 0">
          导入的每一条都按普通提交处理：先查重、再进审核队列，审核通过后才成为成果。
          一次最多 200 条、2 MiB。
        </p>
        <div class="grid cols-2">
          <div>
            <label class="tiny faint" for="import-file">选择文件</label>
            <input
              id="import-file"
              type="file"
              accept=".bib,.ris,.txt,text/plain"
              @change="onImportFile"
            />
            <div v-if="importFileName" class="tiny faint">{{ importFileName }}</div>

            <label class="tiny faint" for="import-format" style="margin-top: 10px; display: block">
              格式
            </label>
            <select id="import-format" v-model="importFormat" style="width: 100%">
              <option value="AUTO">自动识别</option>
              <option value="BIBTEX">BibTeX</option>
              <option value="RIS">RIS</option>
            </select>
            <div class="tiny faint">解析后先给你看预览，确认了才写库。</div>
          </div>
          <div>
            <label class="tiny faint" for="import-text">文件内容（也可以直接粘贴）</label>
            <textarea
              id="import-text"
              v-model="importText"
              rows="8"
              style="width: 100%"
              placeholder="@article{key, title = {...}, year = {2024}}"
              @input="resetImportResults"
            />
          </div>
        </div>

        <div class="btn-row" style="margin-top: 10px">
          <button class="btn" type="button" :disabled="!canPreview" @click="previewImport">
            {{ importBusy === 'preview' ? '解析中…' : '解析预览' }}
          </button>
          <button
            v-if="importPreview && importPreview.parsed > 0"
            class="btn primary"
            type="button"
            :disabled="importBusy !== ''"
            @click="confirmImport"
          >
            {{ importBusy === 'apply' ? '导入中…' : `确认导入 ${importPreview.parsed} 条` }}
          </button>
          <span v-if="!form.scholarId" class="tiny faint">
            未选择关联门户：导入的成果不会建立参与关系
          </span>
        </div>

        <p v-if="importError" class="notice danger">{{ importError }}</p>

        <div v-if="importPreview" style="margin-top: 14px">
          <h3 style="font-size: 14px">
            预览：识别到 {{ importPreview.parsed }} 条（{{ importPreview.format }}）
          </h3>
          <p class="tiny faint">{{ importPreview.note }}</p>
          <table class="data">
            <thead>
              <tr>
                <th style="width: 48px">#</th>
                <th>题名</th>
                <th style="width: 96px">状态</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in importPreview.items" :key="row.index">
                <td class="small">{{ row.index }}</td>
                <td>
                  {{ row.title }}
                  <div v-if="row.citationKey" class="tiny faint mono">{{ row.citationKey }}</div>
                </td>
                <td>
                  <span class="badge" :class="IMPORT_STATUS_CLASS[row.status] ?? 'neutral'">
                    {{ IMPORT_STATUS_LABEL[row.status] || row.status }}
                  </span>
                </td>
                <td class="small">
                  <div v-if="row.error" class="tiny" style="color: var(--danger)">{{ row.error }}</div>
                  <div v-for="(w, i) in row.warnings ?? []" :key="i" class="tiny faint">{{ w }}</div>
                  <span
                    v-if="!row.error && (row.warnings ?? []).length === 0"
                    class="faint"
                  >—</span>
                </td>
              </tr>
            </tbody>
          </table>
          <div v-if="importPreview.skippedNotes.length" class="tiny faint" style="margin-top: 8px">
            <div>另有 {{ importPreview.skippedNotes.length }} 条未纳入：</div>
            <div v-for="(s, i) in importPreview.skippedNotes" :key="i">· {{ s }}</div>
          </div>
        </div>

        <div v-if="importApplied" style="margin-top: 14px">
          <h3 style="font-size: 14px">
            导入结果：提交 {{ importApplied.created }} 条 / 跳过 {{ importApplied.skipped }} 条 /
            失败 {{ importApplied.failed }} 条
          </h3>
          <p class="tiny faint">{{ importApplied.note }}</p>
          <table class="data">
            <thead>
              <tr>
                <th style="width: 48px">#</th>
                <th>题名</th>
                <th style="width: 140px">状态</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in importApplied.items" :key="row.index">
                <td class="small">{{ row.index }}</td>
                <td>{{ row.title }}</td>
                <td>
                  <span class="badge" :class="IMPORT_STATUS_CLASS[row.status] ?? 'neutral'">
                    {{ IMPORT_STATUS_LABEL[row.status] || row.status }}
                  </span>
                </td>
                <td class="small">
                  <div v-if="row.error" class="tiny" style="color: var(--danger)">{{ row.error }}</div>
                  <div v-if="row.duplicateNote" class="tiny faint">{{ row.duplicateNote }}</div>
                  <div v-for="(w, i) in row.warnings ?? []" :key="i" class="tiny faint">{{ w }}</div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <h2>我的提交（{{ items.length }}）</h2>
        <span class="tiny faint">按提交时间倒序，最多 50 条</span>
      </div>
      <div class="panel-body">
        <div v-if="loading" class="loading"><span class="spinner"></span> 正在读取…</div>
        <div v-else-if="items.length === 0" class="empty">还没有提交记录。</div>
        <table v-else class="data">
          <thead>
            <tr>
              <th>题名</th>
              <th>状态</th>
              <th>查重提示</th>
              <th>提交时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in items" :key="row.id">
              <td>
                {{ row.title }}
                <div class="tiny faint mono">{{ row.id }}</div>
              </td>
              <td>
                <span class="badge" :class="STATUS_CLASS[row.status] ?? 'neutral'">
                  {{ row.statusLabel || row.status }}
                </span>
                <div v-if="row.decisionReason" class="tiny faint">{{ row.decisionReason }}</div>
                <RouterLink
                  v-if="row.achievementId"
                  class="tiny"
                  :to="`/achievements/${row.achievementId}`"
                >查看已建档的成果</RouterLink>
              </td>
              <td class="small">
                <template v-if="row.duplicateOf">
                  <span class="badge warn">{{ row.duplicateKind === 'DOI' ? 'DOI 相同' : '题名相似' }}</span>
                  <div class="tiny faint">{{ row.duplicateTitle || row.duplicateOf }}</div>
                  <div class="tiny faint">{{ row.duplicateNote }}</div>
                </template>
                <span v-else class="faint">—</span>
              </td>
              <td class="small">{{ (row.submittedAt || '').slice(0, 16).replace('T', ' ') }}</td>
              <td>
                <button
                  v-if="row.open"
                  class="btn small"
                  type="button"
                  :disabled="busy === row.id"
                  @click="withdraw(row.id)"
                >
                  撤回
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="note" class="tiny faint" style="margin: 10px 0 0">{{ note }}</p>
      </div>
    </div>
  </div>
</template>
