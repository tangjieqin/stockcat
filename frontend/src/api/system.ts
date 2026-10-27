import { http } from './client'

/**
 * 系统状态与数据源（docs/API.md §9）。
 *
 * 同一个接口供两个页面用：系统状态页看"整体健康"，数据源页看"从哪取数、上次跑成没成"。
 * 字段全部只读，且**不含任何凭据**——后端只报有没有配置，不回显值。
 */
export type JobStatusValue = 'UP' | 'FAILED' | 'UNKNOWN'

export interface JobStatus {
  status: JobStatusValue
  lastAttemptAt: string | null
  lastSuccessAt: string | null
  lastError: string | null
  consecutiveFailures: number
  summary: string | null
  attempts: number
  successes: number
}

export interface QuoteCollectorStatus {
  status: 'UP' | 'DEGRADED' | 'UNKNOWN'
  lastSuccessAt: string | null
  lastAttemptAt: string | null
  lastErrorCode: string | null
  consecutiveFailures: number
  attemptCount: number
  successCount: number
}

export interface PythonServiceStatus {
  status: 'UP' | 'DOWN'
  baseUrl: string
  error?: string | null
  agent?: {
    status: string
    modelConfigured: boolean
    model: string | null
    toolCount: number
  }
}

export interface DataSourceItem {
  key: string
  name: string
  provider: string
  baseUrl: string | null
  enabled: boolean
  /** 人话频率，如「交易时段每 15 秒」「交易日 21:30 后」 */
  schedule: string
  /** 定时任务的 cron，页面只在悬停/次行显示，便于排查配置 */
  cron: string | null
  note: string
  /** null = 该数据源没有独立运行统计（如实为空，不伪造） */
  stats: JobStatus | null
}

export interface SystemStatus {
  application: string
  database: string
  quoteCollector: QuoteCollectorStatus
  indicesConfigured: number
  pythonService: PythonServiceStatus
  jobs: Record<string, JobStatus>
  dataSources: DataSourceItem[]
}

export async function getSystemStatus(): Promise<SystemStatus> {
  const resp = await http.get<SystemStatus>('/system/status')
  return resp.data
}
