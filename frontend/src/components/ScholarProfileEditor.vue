<script setup lang="ts">
/**
 * 门户资料编辑（只有该门户的认领人看得到）。
 *
 * 三条设计取舍：
 *
 * 1. **「不改」与「清空」必须能区分**：简介与主页各自有独立按钮吗？没有——那会产生四个按钮。
 *    做法是：只有用户真的改动过的字段才提交（脏字段集合）。没动过的字段不发，服务端按缺省处理，
 *    于是「只改简介」不会顺手把主页清掉。
 * 2. **置顶顺序由点击顺序决定**，并且界面上直接标出名次。让用户勾选一组再靠拖拽排序，
 *    在三条这个量级上只会增加操作成本，而且拖拽在窄屏下几乎不可用。
 * 3. **保存后不做乐观更新**：服务端会重新算「这条成果现在还能不能公开」，本地猜的结果
 *    与服务端不一致时，用户会以为自己保存失败。
 */
import { computed, ref, watch } from 'vue'
import { accountApi } from '@/api'
import { describeError } from '@/composables/useAsync'

interface Candidate {
  achievementId: string
  title?: string | null
  publicationYear?: number | null
  type?: string | null
}

const props = defineProps<{
  scholarId: string
  displayName?: string | null
  researchSummary?: string | null
  homepageUrl?: string | null
  avatarUrl?: string | null
  profileUpdatedAt?: string | null
  featuredIds: string[]
  maxFeatured: number
  candidates: Candidate[]
}>()

const emit = defineEmits<{ saved: []; removed: [] }>()

const TYPE_LABELS: Record<string, string> = {
  PAPER: '论文',
  PATENT: '专利',
  PROJECT: '项目',
  AWARD: '获奖',
}

const summary = ref(props.researchSummary ?? '')
const homepage = ref(props.homepageUrl ?? '')
const featured = ref<string[]>([...props.featuredIds])
const busy = ref('')
const error = ref('')
const note = ref('')

const dirty = computed(
  () =>
    summary.value !== (props.researchSummary ?? '') ||
    homepage.value !== (props.homepageUrl ?? ''),
)

const featuredDirty = computed(
  () => featured.value.join(',') !== props.featuredIds.join(','),
)

// 父组件重新拉取数据后，用服务端返回的值覆盖本地草稿：以服务端为准，避免「本地看着是新的、
// 页面显示是旧的」这种持续到下次刷新才消失的分歧。
watch(
  () => [props.researchSummary, props.homepageUrl, props.featuredIds],
  () => {
    summary.value = props.researchSummary ?? ''
    homepage.value = props.homepageUrl ?? ''
    featured.value = [...props.featuredIds]
  },
)

function rankOf(achievementId: string): number {
  return featured.value.indexOf(achievementId) + 1
}

function toggleFeatured(achievementId: string) {
  error.value = ''
  const index = featured.value.indexOf(achievementId)
  if (index >= 0) {
    featured.value.splice(index, 1)
    return
  }
  if (featured.value.length >= props.maxFeatured) {
    error.value = `最多置顶 ${props.maxFeatured} 条：请先取消其中一条`
    return
  }
  featured.value.push(achievementId)
}

async function saveProfile() {
  if (!dirty.value) {
    return
  }
  busy.value = 'profile'
  error.value = ''
  note.value = ''
  try {
    // 只提交改动过的字段：未改动的字段不出现，服务端按「不改」处理。
    const body: { researchSummary?: string; homepageUrl?: string } = {}
    if (summary.value !== (props.researchSummary ?? '')) body.researchSummary = summary.value
    if (homepage.value !== (props.homepageUrl ?? '')) body.homepageUrl = homepage.value
    await accountApi.updateScholarProfile(props.scholarId, body)
    note.value = '资料已保存并立即生效'
    emit('saved')
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

async function saveFeatured() {
  if (!featuredDirty.value) {
    return
  }
  busy.value = 'featured'
  error.value = ''
  note.value = ''
  try {
    await accountApi.updateScholarFeatured(props.scholarId, featured.value)
    note.value = '置顶已更新'
    emit('saved')
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}

async function uploadAvatar(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  busy.value = 'avatar'
  error.value = ''
  note.value = ''
  try {
    await accountApi.uploadScholarAvatar(props.scholarId, file)
    note.value = '头像已更新'
    emit('saved')
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
    // 清空 input：不清的话选同一个文件第二次不会触发 change。
    input.value = ''
  }
}

async function removeAvatar() {
  busy.value = 'avatar'
  error.value = ''
  note.value = ''
  try {
    await accountApi.removeScholarAvatar(props.scholarId)
    note.value = '头像已移除'
    emit('saved')
  } catch (cause) {
    error.value = describeError(cause).message
  } finally {
    busy.value = ''
  }
}
</script>

<template>
  <div class="panel">
    <div class="panel-head">
      <h2>维护这个门户</h2>
      <span class="tiny faint">
        你是该门户的管理者<template v-if="profileUpdatedAt">
          · 资料最后修改于 {{ profileUpdatedAt.slice(0, 10) }}</template
        >
      </span>
    </div>

    <div class="panel-body">
      <div class="notice" style="margin-bottom: 12px">
        简介、主页与头像属于<strong>本人自述</strong>，保存即生效，公开页会标注来源；
        显示名与机构属于身份与归属事实，仍需提交变更并经审核。
      </div>

      <div class="grid cols-2">
        <div>
          <label class="tiny faint" for="profile-summary">研究简介（最多 2000 字）</label>
          <textarea
            id="profile-summary"
            v-model="summary"
            rows="6"
            maxlength="2000"
            style="width: 100%"
            placeholder="用几句话说清你研究什么、在做什么问题。不要写评价性结论。"
          />
          <div class="tiny faint">{{ summary.length }} / 2000</div>

          <label class="tiny faint" for="profile-homepage" style="margin-top: 10px; display: block">
            个人主页（http/https）
          </label>
          <input
            id="profile-homepage"
            v-model="homepage"
            type="url"
            maxlength="500"
            style="width: 100%"
            placeholder="https://example.edu/~you"
          />

          <div class="btn-row" style="margin-top: 10px">
            <button class="btn primary" type="button" :disabled="!dirty || busy === 'profile'" @click="saveProfile">
              {{ busy === 'profile' ? '保存中…' : '保存资料' }}
            </button>
            <span v-if="!dirty" class="tiny faint">没有改动</span>
          </div>
        </div>

        <div>
          <span class="tiny faint">头像（PNG/JPEG，不超过 2 MiB）</span>
          <div class="btn-row" style="margin-top: 6px; align-items: center">
            <img
              v-if="avatarUrl"
              :src="avatarUrl"
              alt="当前头像"
              style="width: 64px; height: 64px; border-radius: 50%; object-fit: cover; border: 1px solid var(--rule)"
            />
            <span
              v-else
              class="avatar-placeholder"
              aria-hidden="true"
            >{{ (props.displayName || '?').slice(0, 1).toUpperCase() }}</span>
            <label class="btn small" style="cursor: pointer">
              {{ avatarUrl ? '更换头像' : '上传头像' }}
              <input
                type="file"
                accept="image/png,image/jpeg"
                style="display: none"
                :disabled="busy === 'avatar'"
                @change="uploadAvatar"
              />
            </label>
            <button
              v-if="avatarUrl"
              class="btn small"
              type="button"
              :disabled="busy === 'avatar'"
              @click="removeAvatar"
            >
              移除
            </button>
          </div>
          <p class="tiny faint" style="margin: 8px 0 0">
            头像匿名可读，请勿上传含隐私信息的图片；平台只校验文件头，不做内容审核。
          </p>
        </div>
      </div>

      <hr style="margin: 16px 0; border: none; border-top: 1px solid var(--rule)" />

      <div class="panel-head" style="padding: 0 0 8px">
        <h3 style="margin: 0; font-size: 15px">代表作置顶（最多 {{ maxFeatured }} 条）</h3>
        <span class="tiny faint">按点击顺序决定名次；只能选本人已有合格参与关系的公开成果</span>
      </div>

      <div v-if="candidates.length === 0" class="empty">
        暂无可置顶的成果：置顶要求该成果与你的参与关系已确认或自动建立，且当前公开可读。
      </div>
      <ul v-else class="featured-picker">
        <li v-for="row in candidates" :key="row.achievementId" class="featured-option">
          <label style="display: flex; gap: 8px; align-items: flex-start; cursor: pointer">
            <input
              type="checkbox"
              :checked="rankOf(row.achievementId) > 0"
              @change="toggleFeatured(row.achievementId)"
            />
            <span>
              <span v-if="rankOf(row.achievementId) > 0" class="badge ok">
                第 {{ rankOf(row.achievementId) }} 条
              </span>
              {{ row.title || '（来源未提供标题）' }}
              <span class="tiny faint">
                <template v-if="row.publicationYear"> · {{ row.publicationYear }}</template>
                <template v-if="row.type"> · {{ TYPE_LABELS[row.type] ?? row.type }}</template>
              </span>
            </span>
          </label>
        </li>
      </ul>

      <div class="btn-row" style="margin-top: 10px">
        <button
          class="btn primary"
          type="button"
          :disabled="!featuredDirty || busy === 'featured'"
          @click="saveFeatured"
        >
          {{ busy === 'featured' ? '保存中…' : '保存置顶' }}
        </button>
        <span class="tiny faint">已选 {{ featured.length }} / {{ maxFeatured }}</span>
      </div>

      <p v-if="error" class="notice danger" style="margin-top: 12px">{{ error }}</p>
      <p v-if="note" class="notice info" style="margin-top: 12px">{{ note }}</p>
    </div>
  </div>
</template>

<style scoped>
.avatar-placeholder {
  display: inline-flex;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  align-items: center;
  justify-content: center;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 24px;
  font-family: var(--serif, Georgia, serif);
  border: 1px solid var(--rule);
}

.featured-picker {
  list-style: none;
  margin: 0;
  padding: 0;
}

.featured-option {
  padding: 6px 0;
  border-bottom: 1px solid var(--rule);
  font-size: 13px;
}

.featured-option:last-child {
  border-bottom: none;
}
</style>
