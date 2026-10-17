<script setup lang="ts">
/**
 * 成果详情页（公开）。
 *
 * 关键展示约定：**缺失的字段不显示为 0 或空字符串，而是明说「来源未提供」**。
 * 「引用 0」与「来源没说引用」在网上是完全不同的两句话，前者是结论，后者是未知。
 * 同理年份缺失单独写「年份未知」，不补一个默认年份。
 *
 * 字段名以 core 的 PublicCatalogQueryService 实际返回为准（`contributors` 而不是
 * `contributions`，姓名在 `displayName` 而不是 `scholarName`）。这类不一致不会抛错，
 * 只会让页面把「有 3 条合格参与关系」显示成「暂无合格参与关系记录」——把有说成没有，
 * 比报错更难发现，因此这里逐字对齐并在下面每次用到时都写了来源字段。
 */
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { publicApi } from '@/api'
import type { PublicAchievementDetail } from '@/api/types'
import { useAsync } from '@/composables/useAsync'
import { CITATION_FORMATS, type CitationFormatKey } from '@/composables/useCitation'
import AsyncBlock from '@/components/AsyncBlock.vue'
import NatureBadge from '@/components/NatureBadge.vue'

const route = useRoute()
const id = computed(() => String(route.params.id ?? ''))

const detail = useAsync<PublicAchievementDetail & Record<string, unknown>>(() =>
  publicApi.achievement(id.value),
)

interface PaperView {
  doi?: string | null
  venue?: string | null
  publishedDate?: string | null
  preprintStatus?: string | null
  citationCount?: number | null
  citationSource?: string | null
  abstractText?: string | null
  citationNote?: string
}

/** 参与关系。字段名对齐 PublicCatalogQueryService.contributorViews。 */
interface ContributorView {
  scholarId: string
  displayName?: string | null
  organizationName?: string | null
  role?: string | null
  authorOrder?: number | null
  relationState?: string | null
  qualified?: boolean
  relationNote?: string | null
}

const paper = computed<PaperView | null>(() => {
  const raw = detail.data.value?.paper
  if (!raw || typeof raw !== 'object') return null
  return raw as unknown as PaperView
})

/**
 * 专利 / 项目 / 获奖的专属字段。
 *
 * 三类成果的扩展表由后端按类型装配（PublicCatalogQueryService.detailViewOf），页面此前只渲染了
 * paper 一块——于是打开任何一条专利、项目或获奖详情，除了标题与年份之外什么都没有。四类成果是
 * FR16 的硬性验收点，字段看不见等于没有实现。
 *
 * 字段名逐个对齐后端：专利 applicationNo/publicationNo/ipcCodes/applicantNames/filingDate/
 * publicationDate；项目 projectNo/leaderName/fundingAgency/projectCategory；获奖 awardName/
 * awardYear/awardLevel/grantingBody。
 */
interface PatentView {
  applicationNo?: string | null
  publicationNo?: string | null
  ipcCodes?: string[] | null
  applicantNames?: string[] | null
  filingDate?: string | null
  publicationDate?: string | null
}

interface ProjectView {
  projectNo?: string | null
  leaderName?: string | null
  fundingAgency?: string | null
  projectCategory?: string | null
  fundingNote?: string | null
}

interface AwardView {
  awardName?: string | null
  awardYear?: number | null
  awardLevel?: string | null
  grantingBody?: string | null
}

function blockOf<T>(key: string): T | null {
  const raw = detail.data.value?.[key]
  if (!raw || typeof raw !== 'object') return null
  return raw as T
}

const patent = computed(() => blockOf<PatentView>('patent'))
const project = computed(() => blockOf<ProjectView>('project'))
const award = computed(() => blockOf<AwardView>('award'))

/**
 * 来源清单（FR02）。
 *
 * externalUrl 可能为空——来源没给原站地址时不拼一个看起来能点的链接，直接写「来源未提供可跳转地址」。
 * 外链一律 target=_blank + rel=noopener：这是跳原站，不是站内导航，且不能把本页交给对方窗口。
 */
interface SourceRefView {
  sourceCode?: string | null
  displayName?: string | null
  sourceRecordId?: string | null
  externalUrl?: string | null
  licenseScope?: string | null
  dataNature?: string | null
  sourceUpdatedAt?: string | null
  receivedAt?: string | null
}

const sources = computed<SourceRefView[]>(
  () => (detail.data.value?.sources as SourceRefView[] | undefined) ?? [],
)

/** 时间只显示到分钟：来源侧时间用于「新旧判断」，秒级精度对读者没有意义且更易被误读为精确时刻。 */
function shortTime(value: string | null | undefined): string {
  if (!value) return '来源未提供'
  return value.replace('T', ' ').slice(0, 16)
}

function listOrUnknown(values: string[] | null | undefined): string {
  return values && values.length ? values.join('、') : '来源未提供'
}

/**
 * 标题下方那行「出处」。
 *
 * 四类成果的「出处」不是同一个概念：论文看载体、专利看申请人、项目看资助机构、获奖看颁奖机构。
 * 统一写「来源未提供发表载体」会让一条获奖记录看起来像是缺了论文字段。
 */
const subtitleText = computed(() => {
  const type = String(detail.data.value?.type ?? '')
  if (type === 'PATENT') return patent.value?.applicantNames?.join('、') || '申请人未提供'
  if (type === 'PROJECT') return project.value?.fundingAgency || '资助机构未提供'
  if (type === 'AWARD') return award.value?.grantingBody || '颁奖机构未提供'
  return paper.value?.venue || '来源未提供发表载体'
})

const redirectedFrom = computed(() => detail.data.value?.redirectedFrom as string | undefined)

/**
 * 是否存在系统推断出来的领域。
 *
 * 口径说明只在真的出现推断标注时才显示：一条成果的领域全部来自来源时贴上这段解释，
 * 等于暗示这份数据可能被猜过，反而削弱了来源本身的权威性。
 */
const hasInferredTopic = computed(() =>
  (detail.data.value?.topics ?? []).some((topic) => topic.assignmentKind === 'INFERRED'),
)

/** 关系状态是不是「可作为确定合作展示」。判据与后端 qualifiedForGraph 一致。 */
function isQualified(row: ContributorView): boolean {
  return row.qualified === true || row.relationState === 'AUTO' || row.relationState === 'SELF_CONFIRMED'
}

const contributors = computed<ContributorView[]>(() =>
  [...((detail.data.value?.contributors as ContributorView[] | undefined) ?? [])].sort(
    (a, b) => (a.authorOrder ?? 0) - (b.authorOrder ?? 0),
  ),
)

const qualifiedContributors = computed(() => contributors.value.filter(isQualified))

/**
 * 待核验 / 有争议 / 已排除的参与关系。
 *
 * 它们**不进作者行**：署名行是对「谁是作者」的断言，把一条证据不足的关系排进去，
 * 就等于把它说成了确定合作（业务红线）。但也不隐藏——藏起来会让读者以为这个人
 * 与该项成果无关，而事实是「有关系、但尚未确认」。因此单独计数并注明。
 */
const unqualifiedContributors = computed(() => contributors.value.filter((row) => !isQualified(row)))

/**
 * 作者行。
 *
 * 只列关系合格的参与，按署名序排列。姓名缺失时用标识占位而不是省略——这是为了让
 * 「这位作者的身份还没确认」这件事可见，而不是让人数凭空少一位。
 */
const authorLine = computed(() =>
  qualifiedContributors.value.map((row) => row.displayName || `（身份未确认 ${row.scholarId}）`),
)

/** 角色的中文说明。只给代号，读者读不出 AUTHOR 与其它角色的区别。 */
const ROLE_LABELS: Record<string, string> = {
  AUTHOR: '作者',
  CO_AUTHOR: '合作者',
  CORRESPONDING: '通讯作者',
  FIRST_AUTHOR: '第一作者',
  ADVISOR: '导师',
  INVENTOR: '发明人',
  LEADER: '负责人',
  PARTICIPANT: '参与人',
}

function roleLabel(role: string | null | undefined): string {
  if (!role) return '角色未标注'
  return ROLE_LABELS[role] ? `${ROLE_LABELS[role]}` : role
}

const RELATION_LABELS: Record<string, string> = {
  AUTO: '自动建立',
  SELF_CONFIRMED: '本人确认',
  PENDING_VERIFY: '待核验',
  DISPUTED: '存在争议',
  EXCLUDED: '已排除',
}

function relationLabel(state: string | null | undefined): string {
  if (!state) return '关系状态未知'
  return RELATION_LABELS[state] ?? state
}

const typeLabel = computed(() => {
  const label = detail.data.value?.typeLabel
  return typeof label === 'string' && label ? label : String(detail.data.value?.type ?? '')
})

/** 复制引用文本。剪贴板 API 在非安全上下文（http）下不可用，因此要给出失败反馈。 */
const copied = ref('')

async function copyCitation(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = '已复制到剪贴板。'
  } catch {
    copied.value = '浏览器拒绝了剪贴板写入（常见于非 HTTPS 页面），请手动选中上面的文本复制。'
  }
  window.setTimeout(() => {
    copied.value = ''
  }, 6000)
}

/* ------------------------------- 引用导出 ------------------------------- */

const citationFormat = ref<CitationFormatKey>('gb7714')

/**
 * 导出用的数据快照。
 *
 * 作者只取关系合格的那些：署名是断言，把待核验的关系写进引用文本，等于替来源
 * 确认了一件尚未确认的事，而引用会被带出平台、再也看不到那条状态标注。
 */
const citationSource = computed(() => ({
  achievementId: id.value,
  type: String(detail.data.value?.type ?? ''),
  typeLabel: typeLabel.value,
  title: String(detail.data.value?.title ?? ''),
  authors: qualifiedContributors.value.map((row) => row.displayName || `（身份未确认 ${row.scholarId}）`),
  year: (detail.data.value?.publicationYear as number | null) ?? null,
  venue: paper.value?.venue ?? null,
  doi: paper.value?.doi ?? null,
  // 导出日期取本地日期：引用需要说明「什么时候取的」，跨时区偏差一天不影响可追溯性。
  exportedOn: new Date().toLocaleDateString('sv-SE'),
}))

const activeCitationFormat = computed(
  () => CITATION_FORMATS.find((format) => format.key === citationFormat.value) ?? CITATION_FORMATS[0]!,
)

const citationText = computed(() => activeCitationFormat.value.build(citationSource.value))

onMounted(detail.run)
</script>

<template>
  <div>
    <AsyncBlock
      :loading="detail.loading.value"
      :error="detail.error.value"
      :error-code="detail.errorCode.value"
      :empty="detail.data.value === null && !detail.loading.value && !detail.error.value"
      empty-text="该成果不存在，或当前不具备公开资格。"
    >
      <template v-if="detail.data.value">
        <div v-if="redirectedFrom" class="notice info">
          该成果已被合并到另一个实体（原标识 <span class="mono">{{ redirectedFrom }}</span>），
          本页展示的是合并后的权威记录。旧链接不会指向错误对象，但两者不是两条记录。
        </div>

        <h1 style="margin-bottom: 8px; font-size: 26px; line-height: 1.35">
          {{ detail.data.value.title }}
        </h1>

        <div v-if="authorLine.length" class="result-authors" style="font-size: 13.5px">
          {{ authorLine.join('、') }}
        </div>
        <div v-else class="small faint" style="margin-top: 2px">
          署名信息待核验：本成果尚无关系合格的参与记录，因此不列出作者。
        </div>
        <div v-if="unqualifiedContributors.length" class="tiny" style="margin-top: 3px">
          另有 {{ unqualifiedContributors.length }} 条参与关系处于待核验、争议或已排除状态，
          <strong>未计入上面的署名行</strong>（见下方「参与关系」）。
        </div>

        <div class="result-source" style="margin-bottom: 12px">
          {{ subtitleText }}
          ·
          <template v-if="detail.data.value.publicationYear">
            {{ detail.data.value.publicationYear }} 年
          </template>
          <template v-else>年份未知（来源未提供）</template>
        </div>

        <div class="result-meta" style="margin-bottom: 16px">
          <NatureBadge :nature="detail.data.value.dataNature" />
          <span class="badge neutral">{{ typeLabel }}</span>
          <span class="badge ok">{{ detail.data.value.effectiveStatus }}</span>
          <span class="badge ok">{{ detail.data.value.visibility }}</span>
          <span class="tiny faint mono">标识 {{ detail.data.value.id }} · 版本 v{{ detail.data.value.version }}</span>
        </div>

        <div class="grid cols-2">
          <div class="panel">
            <div class="panel-head"><h2>成果信息</h2></div>
            <div class="panel-body">
              <dl class="dl">
                <dt>标识</dt>
                <dd class="mono">{{ detail.data.value.id }}</dd>
                <template v-if="paper">
                  <dt>DOI</dt>
                  <dd>
                    <span v-if="paper.doi" class="mono">{{ paper.doi }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>发表载体</dt>
                  <dd>
                    <span v-if="paper.venue">{{ paper.venue }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>发表日期</dt>
                  <dd>
                    <span v-if="paper.publishedDate" class="mono">{{ paper.publishedDate }}</span>
                    <span v-else class="faint">
                      来源未提供完整日期（只有年份时不补 1 月 1 日）
                    </span>
                  </dd>
                  <dt>引用量</dt>
                  <dd>
                    <template v-if="paper.citationCount !== null && paper.citationCount !== undefined">
                      {{ paper.citationCount }}
                      <span class="badge neutral">{{ paper.citationSource ?? '来源未标注' }}</span>
                      <div class="tiny faint">{{ paper.citationNote }}</div>
                    </template>
                    <span v-else class="faint">来源未提供引用量</span>
                  </dd>
                </template>

                <!-- 专利：申请号与公开号分开列。二者不是同一件事，合并成「专利号」会让读者
                     把「已申请」误读成「已授权公开」。 -->
                <template v-if="patent">
                  <dt>申请号</dt>
                  <dd>
                    <span v-if="patent.applicationNo" class="mono">{{ patent.applicationNo }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>公开号</dt>
                  <dd>
                    <span v-if="patent.publicationNo" class="mono">{{ patent.publicationNo }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>申请人</dt>
                  <dd>{{ listOrUnknown(patent.applicantNames) }}</dd>
                  <dt>申请日</dt>
                  <dd>
                    <span v-if="patent.filingDate" class="mono">{{ patent.filingDate }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>公开日</dt>
                  <dd>
                    <span v-if="patent.publicationDate" class="mono">{{ patent.publicationDate }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>IPC 分类</dt>
                  <dd>{{ listOrUnknown(patent.ipcCodes) }}</dd>
                </template>

                <!-- 项目：不显示经费金额（BR-IMP-11 数据最小化，后端也不返回该字段）。 -->
                <template v-if="project">
                  <dt>项目编号</dt>
                  <dd>
                    <span v-if="project.projectNo" class="mono">{{ project.projectNo }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>负责人</dt>
                  <dd>
                    <span v-if="project.leaderName">{{ project.leaderName }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>资助机构</dt>
                  <dd>
                    <span v-if="project.fundingAgency">{{ project.fundingAgency }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>项目类别</dt>
                  <dd>
                    <span v-if="project.projectCategory">{{ project.projectCategory }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>经费</dt>
                  <dd class="faint">{{ project.fundingNote ?? '本平台不展示经费金额' }}</dd>
                </template>

                <template v-if="award">
                  <dt>奖项名称</dt>
                  <dd>
                    <span v-if="award.awardName">{{ award.awardName }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>获奖年度</dt>
                  <dd>
                    <span v-if="award.awardYear">{{ award.awardYear }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>奖项级别</dt>
                  <dd>
                    <span v-if="award.awardLevel">{{ award.awardLevel }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                  <dt>颁奖机构</dt>
                  <dd>
                    <span v-if="award.grantingBody">{{ award.grantingBody }}</span>
                    <span v-else class="faint">来源未提供</span>
                  </dd>
                </template>
              </dl>
            </div>
          </div>

          <div class="panel">
            <div class="panel-head">
              <h2>参与关系</h2>
              <span class="tiny faint">
                共 {{ contributors.length }} 条，其中合格 {{ qualifiedContributors.length }} 条
              </span>
            </div>
            <div class="panel-body">
              <div v-if="contributors.length === 0" class="empty">
                该成果暂无参与关系记录。这不表示没有作者，只表示来源或平台尚未建立可展示的关系。
              </div>
              <div v-else class="scroll-x">
                <table class="data">
                  <thead>
                    <tr>
                      <th class="num">序</th>
                      <th>学者</th>
                      <th style="white-space: nowrap">角色</th>
                      <th style="white-space: nowrap">关系状态</th>
                      <th>成果发生时机构</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="row in contributors" :key="row.scholarId + String(row.authorOrder)">
                      <td class="num">{{ row.authorOrder ?? '—' }}</td>
                      <td>
                        <RouterLink :to="`/scholars/${row.scholarId}`">
                          {{ row.displayName || `（身份未确认）` }}
                        </RouterLink>
                        <div v-if="!row.displayName" class="tiny faint mono">{{ row.scholarId }}</div>
                      </td>
                      <td class="small" :title="row.role ?? ''">{{ roleLabel(row.role) }}</td>
                      <td>
                        <span class="badge" :class="isQualified(row) ? 'ok' : 'warn'">
                          {{ relationLabel(row.relationState) }}
                        </span>
                        <div class="tiny faint" style="margin-top: 2px">{{ row.relationNote }}</div>
                      </td>
                      <td class="small">
                        <template v-if="row.organizationName">{{ row.organizationName }}</template>
                        <span v-else class="faint">未知（不折算为学者现职机构）</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-if="unqualifiedContributors.length" class="tiny dim" style="margin: 10px 0 0">
                上表中<span class="badge warn">待核验 / 存在争议 / 已排除</span>的行仅作留存展示，
                不代表确定合作关系，也不参与合作网络建边与成果计数。
              </p>
            </div>
          </div>
        </div>

        <div v-if="paper" class="panel">
          <div class="panel-head">
            <h2>摘要</h2>
            <span class="tiny faint">来源原文，未经改写</span>
          </div>
          <div class="panel-body">
            <p v-if="paper?.abstractText" style="margin: 0; white-space: pre-wrap">
              {{ paper.abstractText }}
            </p>
            <div v-else class="empty">来源未提供摘要。</div>
          </div>
        </div>

        <!-- 来源清单（FR02）：一条成果可以有多处来源，这里逐条给出可核对的信息与外链。
             外链只跳原站，不承诺全文；没有外链时明说，不拼一个大概能点的地址。 -->
        <div class="panel">
          <div class="panel-head">
            <h2>来源</h2>
            <span class="tiny faint">
              共 {{ detail.data.value.sourceCount ?? sources.length }} 处可公开来源
              · 外链只跳原站，不代表本平台已核验全文
            </span>
          </div>
          <div class="panel-body">
            <div v-if="sources.length === 0" class="empty">
              本条记录暂无仍具展示许可的来源引用。这不等于数据来源不明，只表示当前没有可对外披露的来源条目。
            </div>
            <table v-else class="data">
              <thead>
                <tr>
                  <th>来源</th>
                  <th>来源侧记录号</th>
                  <th>许可范围</th>
                  <th>数据性质</th>
                  <th>来源更新时间</th>
                  <th>原站</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="source in sources" :key="String(source.sourceCode) + String(source.sourceRecordId)">
                  <td>
                    {{ source.displayName ?? source.sourceCode ?? '来源未标注' }}
                    <div class="tiny faint mono">{{ source.sourceCode }}</div>
                  </td>
                  <td class="mono tiny">{{ source.sourceRecordId ?? '—' }}</td>
                  <td class="tiny">{{ source.licenseScope ?? '未提供许可说明' }}</td>
                  <td class="tiny">{{ source.dataNature ?? '未知' }}</td>
                  <td class="tiny">{{ shortTime(source.sourceUpdatedAt) }}</td>
                  <td class="tiny">
                    <a
                      v-if="source.externalUrl"
                      :href="source.externalUrl"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      打开原站
                    </a>
                    <span v-else class="faint">来源未提供可跳转地址</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p class="tiny faint" style="margin: 10px 0 0">
              外链失效不影响本条元数据可读：平台保存的是来源记录，不是原站页面。
              同一成果的多处来源不重复计数，也不把来源条数当作成果条数。
            </p>
          </div>
        </div>

        <div v-if="(detail.data.value.topics ?? []).length" class="panel">
          <div class="panel-head"><h2>领域标注</h2></div>
          <div class="panel-body">
            <div class="btn-row">
              <RouterLink
                v-for="topic in detail.data.value.topics"
                :key="topic.topicId"
                :to="`/topics/${topic.topicId}`"
                class="badge"
                :class="topic.assignmentKind === 'INFERRED' ? 'warn' : 'neutral'"
              >
                {{ topic.canonicalName
                }}<template v-if="topic.assignmentKind === 'INFERRED'">
                  · 系统推断{{ topic.confidence != null ? ` ${(topic.confidence * 100).toFixed(1)}%` : '' }}
                </template>
              </RouterLink>
            </div>
            <p v-if="hasInferredTopic" class="faint tiny" style="margin: 10px 0 0">
              带「系统推断」的领域是平台按受控词表从题名、期刊与摘要判定的，来源未提供学科分类。
              它用于分类检索与领域统计，不代表来源声明，也不参与任何以来源为准的判定。
            </p>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <h2>引用这条记录</h2>
            <span class="tiny faint">
              导出用的是本平台整理后的元数据，不是出版方页面的权威引用
            </span>
          </div>
          <div class="panel-body">
            <div class="btn-row" role="tablist" aria-label="引用格式">
              <button
                v-for="format in CITATION_FORMATS"
                :key="format.key"
                class="btn"
                :class="{ primary: format.key === citationFormat }"
                role="tab"
                :aria-selected="format.key === citationFormat"
                @click="citationFormat = format.key"
              >
                {{ format.label }}
              </button>
              <span style="flex: 1"></span>
              <button class="btn no-print" @click="copyCitation(citationText)">复制</button>
            </div>

            <p class="tiny dim" style="margin: 10px 0 0">{{ activeCitationFormat.note }}</p>

            <pre class="pre" style="margin-top: 10px" aria-live="polite">{{ citationText }}</pre>

            <p v-if="copied" class="tiny" style="margin: 6px 0 0">{{ copied }}</p>

            <p class="tiny faint" style="margin: 10px 0 0">
              平台未采集卷号、期号与页码，因此这三种格式里都不会出现这三项——
              这是「不知道」，不是「没有」。需要完整著录信息时，请以来源页面为准。
            </p>
          </div>
        </div>
      </template>
    </AsyncBlock>
  </div>
</template>
