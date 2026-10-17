/**
 * 统一 HTTP 客户端。
 *
 * 三件事必须集中做，散在每个调用点必然会漏：
 *
 * 1. **信封拆解**：三个服务统一返回 `{code, message, data, traceId}`。业务失败是 HTTP 200 +
 *    业务码（例如 VIEW_UPDATING），也可能是 HTTP 4xx/5xx。两种都要转成同一种异常，否则调用方
 *    得写两套判断，而漏掉的那一套通常就是「200 里带错误码」这条。
 * 2. **追踪号透传**：失败时把 traceId 一并带出来。没有它，用户报「检索失败了」时只能靠时间
 *    对齐日志；带上它可以直接定位到那一次请求。
 * 3. **CSRF 双提交**：core 的写操作要求 Cookie 与请求头一致。从 Cookie 读值再写进请求头，
 *    漏掉任何一处写操作都会得到 403，而 403 的文案看起来像是权限不足，很容易被误判成后端问题。
 */

export type ServiceKey = 'core' | 'query' | 'ingest'

const PREFIX: Record<ServiceKey, string> = {
  core: '/core',
  query: '/query',
  ingest: '/ingest',
}

/** 业务异常：带业务码与追踪号，便于界面直接展示可操作的信息。 */
export class ApiError extends Error {
  readonly code: string
  readonly traceId: string
  readonly httpStatus: number

  constructor(code: string, message: string, traceId: string, httpStatus: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
    this.httpStatus = httpStatus
  }

  /** 视图正在更新：这是正常的过渡态，界面应提示稍后重试而不是显示「出错」。 */
  get isViewUpdating(): boolean {
    return this.code === 'VIEW_UPDATING'
  }

  /** 查询版本已变化：调用方应保留条件、回到第一页重新发起。 */
  get isQueryVersionChanged(): boolean {
    return this.code === 'QUERY_VERSION_CHANGED'
  }

  get isAuthRequired(): boolean {
    return this.httpStatus === 401 || this.code === 'AUTH_REQUIRED'
  }

  get isAccessDenied(): boolean {
    return this.httpStatus === 403 || this.code === 'ACCESS_DENIED'
  }

  get isDependencyUnavailable(): boolean {
    return this.code === 'DEPENDENCY_UNAVAILABLE'
  }
}

export interface Envelope<T> {
  code: string
  message: string
  data: T
  meta?: Record<string, unknown>
  traceId?: string
  timestamp?: string
}

function readCookie(name: string): string {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const item = part.trim()
    if (item.startsWith(prefix)) {
      return decodeURIComponent(item.slice(prefix.length))
    }
  }
  return ''
}

/**
 * 取一枚新的 CSRF 令牌。
 *
 * <p><b>为什么每次写请求都要重取。</b>core 的 CSRF 校验在通过之后会清掉 XSRF-TOKEN Cookie
 * （响应头带上 `Max-Age=0`），也就是说令牌是<b>一次性</b>的。浏览器里连续做两次写操作时，
 * 第二次手里已经没有令牌：表现不是「权限不足」，而是看起来像偶发的 403——同一段代码有时成功
 * 有时失败，会把人引向错误的方向。此前的实现只从 Cookie 读值、从不再取，于是任何页面上的
 * 第二次写入都必然失败。
 *
 * <p>取令牌的请求本身是 GET，不会消耗令牌；同时并发发起的多次写入共用同一次取令牌，避免
 * 互相把对方的令牌顶掉。
 */
let csrfRefresh: Promise<void> | null = null

function refreshCsrf(): Promise<void> {
  if (!csrfRefresh) {
    csrfRefresh = fetch('/core/api/v1/auth/csrf', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    })
      .then(() => undefined)
      .finally(() => {
        csrfRefresh = null
      })
  }
  return csrfRefresh
}

/**
 * 写请求串行队列。
 *
 * <p>一次性令牌决定了两个写请求不能同时带着同一枚令牌出发：先到的那枚会把它用掉，后到的必然
 * 403。串行化的代价是写操作排队（人的操作本来就是一次一次点的），换来的是「点了就成」。
 */
let writeChain: Promise<unknown> = Promise.resolve()

function serializeWrite<T>(task: () => Promise<T>): Promise<T> {
  const run = writeChain.then(task, task)
  // 队列本身不能被上一次的失败污染：失败要继续放行后续请求，由各自的调用方处理错误。
  writeChain = run.catch(() => undefined)
  return run
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
  query?: Record<string, string | number | boolean | undefined | null>
  body?: unknown
  /** 期望的响应类型。默认 JSON；下载类接口用 blob。 */
  responseType?: 'json' | 'blob'
  /** 关闭自动 CSRF 头。仅用于确实不需要的场合；默认对所有非 GET 都带上。 */
  csrf?: boolean
}

function buildUrl(service: ServiceKey, path: string, query?: RequestOptions['query']): string {
  const url = new URL(`${PREFIX[service]}${path}`, window.location.origin)
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue
      url.searchParams.set(key, String(value))
    }
  }
  return url.pathname + url.search
}

/**
 * 发起一次请求，返回完整信封。
 *
 * <p>需要读 meta 的场景（例如治理工作台要把服务端算出的分档计数与排序白名单直接展示出来）
 * 用这个；只要数据的场景用下面的 {@link request}。
 *
 * @throws ApiError 任何业务码非 OK 或 HTTP 非 2xx 的情况
 */
export async function requestEnvelope<T>(
  service: ServiceKey,
  path: string,
  options: RequestOptions = {},
): Promise<Envelope<T>> {
  const method = options.method ?? 'GET'
  const needsCsrf = options.csrf !== false && method !== 'GET'
  if (!needsCsrf) {
    return send<T>(service, path, options, '')
  }
  return serializeWrite(async () => {
    await refreshCsrf()
    try {
      return await send<T>(service, path, options, readCookie('XSRF-TOKEN'))
    } catch (cause) {
      // 令牌可能在别处已被用掉（同一会话另开一个标签页、或上一条请求刚好消费掉它）。
      // 只在这种情况下重取一枚再试一次；真正的越权请求重试后仍然会被拒绝，错误照原样上抛，
      // 不会被这次重试掩盖成「重试也没用」的模糊结论。
      if (cause instanceof ApiError && cause.httpStatus === 403) {
        await refreshCsrf()
        return send<T>(service, path, options, readCookie('XSRF-TOKEN'))
      }
      throw cause
    }
  })
}

/** 实际发出一次请求。CSRF 令牌由调用方决定（写请求每次重取，读请求为空）。 */
async function send<T>(
  service: ServiceKey,
  path: string,
  options: RequestOptions,
  csrfToken: string,
): Promise<Envelope<T>> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (csrfToken) {
    headers['X-XSRF-TOKEN'] = csrfToken
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(buildUrl(service, path, options.query), {
      method,
      headers,
      // 会话走 Cookie，必须带上；代理是同源的，无需处理 CORS 凭据问题。
      credentials: 'same-origin',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch (cause) {
    // 网络层失败与业务失败必须区分：前者说明目标服务没起来，后者说明它回答了「不行」。
    // 把两者都报成「请求失败」，排查时会先怀疑代码而不是进程状态。
    throw new ApiError(
      'NETWORK_UNAVAILABLE',
      `无法连接后端服务（${service}）。请确认该服务已启动且代理指向正确。`,
      '',
      0,
    )
  }

  if (options.responseType === 'blob') {
    if (!response.ok) {
      throw new ApiError('HTTP_' + response.status, `下载失败：HTTP ${response.status}`, '', response.status)
    }
    const blob = (await response.blob()) as unknown as T
    return { code: 'OK', message: '', data: blob }
  }

  let envelope: Envelope<T> | null = null
  const text = await response.text()
  if (text) {
    try {
      envelope = JSON.parse(text) as Envelope<T>
    } catch {
      envelope = null
    }
  }

  if (envelope === null) {
    throw new ApiError(
      'MALFORMED_RESPONSE',
      `响应不是合法 JSON（HTTP ${response.status}）。可能是代理指向了非本项目的服务。`,
      '',
      response.status,
    )
  }

  const code = envelope.code ?? ''
  if (!response.ok || (code !== '' && code !== 'OK')) {
    throw new ApiError(
      code || `HTTP_${response.status}`,
      envelope.message || `请求失败：HTTP ${response.status}`,
      envelope.traceId ?? '',
      response.status,
    )
  }
  return envelope
}

/**
 * 发起一次请求并拆掉信封。
 *
 * @throws ApiError 任何业务码非 OK 或 HTTP 非 2xx 的情况
 */
export async function request<T>(
  service: ServiceKey,
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const envelope = await requestEnvelope<T>(service, path, options)
  return envelope.data
}
