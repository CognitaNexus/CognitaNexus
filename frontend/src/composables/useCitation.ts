/**
 * 引用文本生成。
 *
 * 为什么要做：这个平台是给读者「找到文献、拿去用」的。找到了却要手抄一遍引用，
 * 等于把最容易抄错的环节留给用户——年份、DOI、作者顺序抄错一位，引用就是错的。
 *
 * 三条不可让步的规则：
 *
 *  1. **不编字段**。平台不采集卷号、期号、页码，因此导出的 BibTeX 里就没有 volume /
 *     number / pages。留空是「不知道」，填占位符是「编」。缺字段在出口处按各格式的
 *     惯例表达「不详」（APA 用 n.d.、GB/T 7714 用 [出版年不详]），而不是塞 0 或 1900。
 *  2. **不改姓名**。APA 要求「姓, 名首字母」，但平台存的是展示名（displayName），
 *     中英文名混排且无法可靠切分姓与名。强行转换会把「Wang Shiping」变成
 *     「S., Wang」之类的错误结果。因此作者一律按平台记录原样输出，并在界面上说明这一点。
 *  3. **标明出处与时刻**。导出文本里带上平台标识与导出日期，让读者能说明「这条引用
 *     是什么时候、从哪个库取的」——平台的元数据会随后续修订变化，引用需要可追溯。
 */

export type CitationFormatKey = 'gb7714' | 'bibtex' | 'ris' | 'apa'

export interface CitationSource {
  /** 成果标识，写进导出文本便于回溯。 */
  achievementId: string
  type: string
  /** 后端给出的类型中文名（typeLabel），缺失时用空串。 */
  typeLabel?: string
  title: string
  /** 已按署名序排好的合格作者名。 */
  authors: string[]
  year: number | null
  venue: string | null
  doi: string | null
  /** 导出日期，由调用方传入以便测试与快照稳定。 */
  exportedOn: string
}

export interface CitationFormat {
  key: CitationFormatKey
  label: string
  /** 格式的适用范围说明，直接显示给用户。 */
  note: string
  build: (source: CitationSource) => string
}

/** GB/T 7714-2015 的文献类型标志。平台只采集四类成果。 */
const GB_TYPE_CODES: Record<string, string> = {
  PAPER: 'J',
  PATENT: 'P',
  PROJECT: 'R',
  // 获奖成果在 GB/T 7714 里没有专用标志，归入「其他」而不是硬套一个期刊标志。
  AWARD: 'Z',
}

/** RIS 的 TY 取值。 */
const RIS_TYPES: Record<string, string> = {
  PAPER: 'JOUR',
  PATENT: 'PAT',
  PROJECT: 'RPRT',
  AWARD: 'GEN',
}

/** BibTeX 条目类型。标准 BibTeX 没有 @patent / @project / @award，落到 @misc 并注明。 */
const BIBTEX_TYPES: Record<string, string> = {
  PAPER: 'article',
  PATENT: 'misc',
  PROJECT: 'misc',
  AWARD: 'misc',
}

/** 平台来源说明，附在每种格式末尾。 */
function provenance(source: CitationSource): string {
  return `CognitaNexus 学术成果分享平台（记录 ${source.achievementId}，导出于 ${source.exportedOn}）`
}

/**
 * BibTeX 的字段值转义。
 *
 * 只做必要的事：转义花括号与反斜杠（它们是 BibTeX 的语法字符），并把换行压平。
 * 不去动 & % $ # _ ^ ~ 这几个符号——它们在 .bib 正文里合法，擅自加反斜杠会让
 * 标题与数据库中的原文不一致。
 */
function bibValue(value: string): string {
  return value.replace(/\\/g, '\\textbackslash{}').replace(/[{}]/g, (ch) => `\\${ch}`).replace(/\s*\n\s*/g, ' ').trim()
}

/** 生成 BibTeX 的 key：第一作者姓 + 年份 + 标识尾段，保证同一库内不重复。 */
function bibKey(source: CitationSource): string {
  const firstAuthor = source.authors[0] ?? 'anon'
  const surname = firstAuthor.trim().split(/\s+/).slice(-1)[0] ?? 'anon'
  const safe = surname.replace(/[^A-Za-z0-9]/g, '') || 'anon'
  return `${safe}${source.year ?? 'nd'}${source.achievementId.slice(-6)}`
}

function buildGb7714(source: CitationSource): string {
  const code = GB_TYPE_CODES[source.type] ?? 'Z'
  const authors = source.authors.length ? source.authors.join(', ') : '[作者不详]'
  const year = source.year === null ? '[出版年不详]' : `${source.year}`
  const venue = source.venue ? `${source.venue}, ` : ''
  const doi = source.doi ? ` DOI: ${source.doi}.` : ''
  // 文献类型标志紧随题名，这是 GB/T 7714 的固定位置，不放在行尾。
  return `${authors}. ${source.title}[${code}]. ${venue}${year}.${doi}\n${provenance(source)}`
}

function buildBibtex(source: CitationSource): string {
  const entry = BIBTEX_TYPES[source.type] ?? 'misc'
  const lines: string[] = [`@${entry}{${bibKey(source)},`]
  lines.push(`  title = {${bibValue(source.title)}},`)
  if (source.authors.length) {
    lines.push(`  author = {${source.authors.map(bibValue).join(' and ')}},`)
  } else {
    // 没有作者时不留空字段，而是写进 note：空 author 会让 BibTeX 静默忽略整条记录。
    lines.push('  note = {作者不详},')
  }
  if (source.year !== null) lines.push(`  year = {${source.year}},`)
  if (source.venue) {
    lines.push(
      source.type === 'PAPER'
        ? `  journal = {${bibValue(source.venue)}},`
        : `  howpublished = {${bibValue(source.venue)}},`,
    )
  }
  if (source.doi) lines.push(`  doi = {${source.doi}},`)
  // 平台不采集卷期页码，因此这里没有 volume / number / pages，且不填占位值。
  lines.push(`  note = {${bibValue(provenance(source))}},`)
  lines.push('}')
  return lines.join('\n')
}

function buildRis(source: CitationSource): string {
  const lines: string[] = [`TY  - ${RIS_TYPES[source.type] ?? 'GEN'}`]
  for (const author of source.authors) lines.push(`AU  - ${author}`)
  lines.push(`TI  - ${source.title}`)
  if (source.venue) lines.push(`JO  - ${source.venue}`)
  if (source.year !== null) lines.push(`PY  - ${source.year}`)
  if (source.doi) {
    lines.push(`DO  - ${source.doi}`)
    lines.push(`UR  - https://doi.org/${source.doi}`)
  }
  if (source.year === null) lines.push('N1  - 出版年不详（来源未提供）')
  lines.push(`N1  - ${provenance(source)}`)
  lines.push('ER  - ')
  return lines.join('\n')
}

function buildApa(source: CitationSource): string {
  // 姓名按平台记录原样输出，不做「姓, 名首字母」转换（理由见文件头第 2 条）。
  const authors = source.authors.length
    ? source.authors.join(', ') + (source.authors.length > 1 ? '' : '')
    : '作者不详'
  const year = source.year === null ? 'n.d.' : String(source.year)
  const venue = source.venue ? ` ${source.venue}.` : ''
  const doi = source.doi ? ` https://doi.org/${source.doi}` : ''
  return `${authors} (${year}). ${source.title}.${venue}${doi} ${provenance(source)}`
}

export const CITATION_FORMATS: CitationFormat[] = [
  {
    key: 'gb7714',
    label: 'GB/T 7714-2015',
    note: '中文期刊与学位论文要求的国标格式。平台不采集卷、期、页码，因此这三项不会出现。',
    build: buildGb7714,
  },
  {
    key: 'bibtex',
    label: 'BibTeX',
    note: '供 LaTeX / Zotero 导入。平台没有卷期页码字段，故条目里也没有 volume / number / pages；专利、项目、获奖在标准 BibTeX 中无对应类型，落为 @misc。',
    build: buildBibtex,
  },
  {
    key: 'ris',
    label: 'RIS',
    note: '供 EndNote 等文献管理器导入。RIS 的条目类型里没有「获奖」，因此该项落为 GEN（通用）。',
    build: buildRis,
  },
  {
    key: 'apa',
    label: 'APA 第 7 版',
    note: '作者姓名按平台记录原样输出：平台只存展示名，不能可靠切分姓与名，强行转成「姓, 名首字母」会造成错误。',
    build: buildApa,
  },
]
