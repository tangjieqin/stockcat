import axios from 'axios'

/**
 * 统一 API 客户端。
 * 成功：直接返回资源（无 {code,data} 包装，见 docs/API.md §1）。
 * 失败：后端返回 {timestamp,status,code,message,details,path}，此处统一抛出 ApiError。
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly details: string[]

  constructor(status: number, code: string, message: string, details: string[] = []) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

export const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10_000,
})

http.interceptors.response.use(
  (resp) => resp,
  (err) => {
    const body = err.response?.data
    if (body && body.code) {
      throw new ApiError(body.status ?? err.response.status, body.code, body.message ?? '请求失败', body.details ?? [])
    }
    throw new ApiError(err.response?.status ?? 0, 'NETWORK_ERROR', err.message ?? '网络错误')
  },
)
