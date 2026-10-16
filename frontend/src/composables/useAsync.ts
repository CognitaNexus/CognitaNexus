import { ref, shallowRef } from 'vue'
import { ApiError } from '@/api/http'

/**
 * 一次异步读取的加载 / 失败 / 数据三态。
 *
 * 把三态收敛到一处，是为了让「失败必须显式呈现」成为默认行为而不是每次都要记得写的东西。
 * 用 shallowRef 存数据：返回的是接口对象，深层响应式改造没有收益，反而会让大列表的
 * proxy 开销按行数放大。
 */
export function useAsync<T>(loader: () => Promise<T>, initial: T | null = null) {
  const data = shallowRef<T | null>(initial)
  const loading = ref(false)
  const error = ref('')
  const errorCode = ref('')

  async function run(): Promise<void> {
    loading.value = true
    error.value = ''
    errorCode.value = ''
    try {
      data.value = await loader()
    } catch (cause) {
      if (cause instanceof ApiError) {
        error.value = cause.message
        errorCode.value = cause.code
      } else {
        error.value = cause instanceof Error ? cause.message : String(cause)
        errorCode.value = ''
      }
      // 失败时把 data 清空：留着上一次的结果会让「上一次的内容」与「这一次的失败」同时显示，
      // 读者无法判断屏幕上的数字是哪一次的。
      data.value = null
    } finally {
      loading.value = false
    }
  }

  return { data, loading, error, errorCode, run }
}

/** 把任意异常转成可展示的文案与错误码。 */
export function describeError(cause: unknown): { message: string; code: string } {
  if (cause instanceof ApiError) return { message: cause.message, code: cause.code }
  if (cause instanceof Error) return { message: cause.message, code: '' }
  return { message: String(cause), code: '' }
}
