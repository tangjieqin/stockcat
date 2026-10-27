import { http } from './client'

/**
 * AI 助手接口（docs/API.md §11）。
 * 回答体由 Python 契约定义，Java 只做代理与超时控制，前端不做字段改写。
 * 生成一次最长 60s，单独放宽 axios 超时，避免和其他接口共用 10s 被误杀。
 */
export interface AssistantToolCall {
  tool: string
  label: string
  ok: boolean
  summary: string | null
  error: string | null
  elapsedMs: number | null
  dataAsOf: string | null
}

export interface AssistantAnswer {
  conclusion: string
  evidence: string[]
  limits: string[]
}

export interface AssistantUsage {
  model: string
  promptTokens: number | null
  completionTokens: number | null
}

/** 资料引用（P3.3）：citation 由后端拼好，documentId/seq 用来跳回原文那一段。 */
export interface AssistantCitation {
  chunkId: number
  documentId: number
  title: string
  seq: number
  citation: string
}

export interface AssistantReply {
  question: string
  /** llm = 模型生成；rule_based = 规则汇总（未配置模型或模型失败） */
  mode: 'llm' | 'rule_based'
  answer: AssistantAnswer
  toolCalls: AssistantToolCall[]
  citations?: AssistantCitation[]
  dataAsOf: string | null
  generatedAt: string
  model: string | null
  usage: AssistantUsage | null
  degraded: string[]
  elapsedMs: number
}

export interface AssistantStatus {
  available: boolean
  agent?: {
    status: string
    modelConfigured: boolean
    model: string | null
    tools: string[]
  }
  error?: string
  baseUrl: string
}

export async function askAssistant(question: string): Promise<AssistantReply> {
  const resp = await http.post<AssistantReply>('/assistant/ask', { question }, { timeout: 70_000 })
  return resp.data
}

export async function getAssistantStatus(): Promise<AssistantStatus> {
  const resp = await http.get<AssistantStatus>('/assistant/status')
  return resp.data
}
