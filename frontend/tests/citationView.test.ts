/**
 * 引用面板展示层的纯函数测试（node:test 直跑，不需要浏览器与 Vue）。
 *
 * 运行：cd frontend && npm test
 *
 * 这组用例只覆盖「服务端给了数据之后，界面怎么选、怎么标、怎么拼下载地址」。引用文本本身的正确性
 * 在服务端：格式规范细节（国标三位作者截断、APA 的 & 连接、BibTeX 十个字符转义、撤稿进文本）
 * 由 `CitationFormatterTest` 逐条断言。把同一批规则再在前端测一遍只会得到两份会漂移的实现——
 * 上一版正是如此，而且它测的是自己那份有缺陷的实现。
 *
 * 这里守的是三个「错了也不报错」的地方：
 *  1. 下载地址少拼服务前缀 → 浏览器直接打后端端口，跨源丢 Cookie，表现为 401 而不是 404；
 *  2. 告警严重度一律标成红 → 「年份缺失」和「已撤稿」被读成同一件事；
 *  3. 告警不去重 → 同一句话出现两遍，读者以为遇到了两个不同的问题。
 */
import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  activeFormat,
  batchSummary,
  batchWarnings,
  citeKeyLabel,
  downloadHref,
  mergeWarnings,
  pruneSelected,
  toggleSelectAll,
  toggleSelected,
  warningTone,
} from '../src/composables/citationView.ts'

const FORMATS = [
  { code: 'bibtex', label: 'BibTeX', text: '@article{k,\n}', fileName: 'k.bib', mediaType: 'application/x-bibtex', machineReadable: true, downloadUrl: '/api/v1/public/achievements/a/citation?format=bibtex', warnings: [] },
  { code: 'gb_t_7714', label: 'GB/T 7714-2015', text: '张三. 题名[J]. 某刊, 2023.', fileName: 'k.txt', mediaType: 'text/plain', machineReadable: false, downloadUrl: '/api/v1/public/achievements/a/citation?format=gb_t_7714', warnings: [] },
]

const BUNDLE = {
  achievementId: 'a',
  citeKey: 'zhangsan2023item_3d4e5f',
  defaultFormat: 'gb_t_7714',
  provenance: 'CognitaNexus 学术成果分享平台',
  exportedOn: '2026-10-18',
  warnings: [],
  formats: FORMATS,
  note: '',
}

test('下载地址补上服务前缀，已带前缀不重复拼', () => {
  assert.equal(
    downloadHref('/api/v1/public/achievements/a/citation?format=bibtex'),
    '/core/api/v1/public/achievements/a/citation?format=bibtex',
  )
  assert.equal(downloadHref('/core/api/v1/x'), '/core/api/v1/x')
})

test('下载地址只接受站内路径，绝对地址与空值返回空串', () => {
  assert.equal(downloadHref('https://example.org/x.bib'), '')
  assert.equal(downloadHref('api/v1/x'), '')
  assert.equal(downloadHref(''), '')
})

test('告警严重度：撤稿红、更正与关注橙、信息不全灰', () => {
  assert.equal(warningTone('RETRACTED'), 'danger')
  assert.equal(warningTone('CORRECTED'), 'warn')
  assert.equal(warningTone('CONCERNED'), 'warn')
  assert.equal(warningTone('DATA_NATURE_NOT_REAL'), 'warn')
  assert.equal(warningTone('YEAR_UNKNOWN'), 'info')
  assert.equal(warningTone('VENUE_MISSING'), 'info')
  assert.equal(warningTone('SOMETHING_NEW'), 'info')
})

test('选中格式：按 code 命中；未命中回落服务端默认；再回落第一个', () => {
  assert.equal(activeFormat(BUNDLE, 'bibtex')?.code, 'bibtex')
  assert.equal(activeFormat(BUNDLE, 'ris')?.code, 'gb_t_7714')
  assert.equal(activeFormat({ ...BUNDLE, defaultFormat: 'nope' }, 'nope')?.code, 'bibtex')
  assert.equal(activeFormat(null, 'bibtex'), null)
  assert.equal(activeFormat({ ...BUNDLE, formats: [] }, 'bibtex'), null)
})

test('告警合并去重且保持顺序，元数据告警在前', () => {
  const merged = mergeWarnings(
    [{ code: 'YEAR_UNKNOWN', message: '来源未提供年份' }],
    [
      { code: 'NAME_SPLIT_NOT_APPLIED', message: '未拆分姓名' },
      { code: 'YEAR_UNKNOWN', message: '重复的年份告警' },
    ],
  )
  assert.deepEqual(
    merged.map((w) => w.code),
    ['YEAR_UNKNOWN', 'NAME_SPLIT_NOT_APPLIED'],
  )
  assert.equal(merged[0]?.message, '来源未提供年份')
})

test('引用键缺省时给出明确文案而不是空白', () => {
  assert.equal(citeKeyLabel('zhangsan2023item_3d4e5f'), 'zhangsan2023item_3d4e5f')
  assert.equal(citeKeyLabel(''), '未生成引用键')
})

/* ------------------------------ 批量导出 ------------------------------ */

const BATCH = {
  format: 'bibtex',
  formatLabel: 'BibTeX',
  mediaType: 'application/x-bibtex',
  machineReadable: true,
  fileName: 'citations-bibtex-2026-10-18.bib',
  requested: 3,
  exported: 3,
  skippedCount: 0,
  entries: [
    { achievementId: 'a', citeKey: 'k1', text: '@article{k1,\n}', warnings: [{ code: 'YEAR_UNKNOWN', message: '来源未提供年份' }] },
    { achievementId: 'b', citeKey: 'k2', text: '@article{k2,\n}', warnings: [{ code: 'YEAR_UNKNOWN', message: '来源未提供年份（另一条）' }] },
  ],
  skipped: [],
  warnings: [],
  text: '@article{k1,\n}\n\n@article{k2,\n}\n',
  provenance: 'CognitaNexus 学术成果分享平台',
  exportedOn: '2026-10-18',
  downloadUrl: '/api/v1/public/citations/batch/download?format=bibtex&ids=a&ids=b',
  note: '',
}

test('勾选切换不就地修改原数组', () => {
  const original = ['a']
  const added = toggleSelected(original, 'b')
  assert.deepEqual(added, ['a', 'b'])
  assert.deepEqual(original, ['a'])
  assert.deepEqual(toggleSelected(added, 'a'), ['b'])
})

test('结果集变化后剪掉看不见的勾选', () => {
  assert.deepEqual(pruneSelected(['a', 'b', 'c'], ['b', 'c', 'd']), ['b', 'c'])
  assert.deepEqual(pruneSelected(['a'], []), [])
})

test('全选在已全选时改为清空，不会重复追加', () => {
  assert.deepEqual(toggleSelectAll([], ['a', 'b']), ['a', 'b'])
  assert.deepEqual(toggleSelectAll(['a', 'b'], ['a', 'b']), [])
  assert.deepEqual(toggleSelectAll(['a'], ['a', 'b']), ['a', 'b'])
  // 空结果页不能因为「every 对空数组为真」而被判成已全选
  assert.deepEqual(toggleSelectAll(['a'], []), [])
})

test('批量导出结论说清少给了几条', () => {
  assert.equal(batchSummary(BATCH), '已导出 3 条引用')
  assert.equal(
    batchSummary({ ...BATCH, requested: 5, exported: 3, skippedCount: 2 }),
    '已导出 3 条引用，2 条不可公开已跳过（共选中 5 条）',
  )
})

test('批量告警按 code 合并去重，条目级重复只留一句', () => {
  const merged = batchWarnings(BATCH)
  assert.deepEqual(
    merged.map((w) => w.code),
    ['YEAR_UNKNOWN'],
  )
  // 保留的是先出现的那条说明，而不是最后一条
  assert.equal(merged[0]?.message, '来源未提供年份')
  assert.deepEqual(
    batchWarnings({ ...BATCH, warnings: [{ code: 'CITE_KEY_DISAMBIGUATED', message: '键已改' }] }).map(
      (w) => w.code,
    ),
    ['CITE_KEY_DISAMBIGUATED', 'YEAR_UNKNOWN'],
  )
})
