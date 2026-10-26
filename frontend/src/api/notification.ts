import { http } from './client'

/** 通知记录（docs/API.md §5） */
export interface NotificationRecord {
  id: number
  alertEventId: number | null
  channel: string
  status: 'PENDING' | 'SENT' | 'FAILED'
  providerMessageId: string | null
  attemptCount: number
  lastErrorCode: string | null
  lastErrorMessage: string | null
  createdAt: string
  sentAt: string | null
}

export async function listNotifications(status?: string): Promise<NotificationRecord[]> {
  const resp = await http.get<NotificationRecord[]>('/notifications', {
    params: status ? { status } : {},
  })
  return resp.data
}

export async function retryNotification(id: number): Promise<NotificationRecord> {
  const resp = await http.post<NotificationRecord>(`/notifications/${id}/retry`)
  return resp.data
}

export async function testNotification(): Promise<NotificationRecord> {
  const resp = await http.post<NotificationRecord>('/notifications/test')
  return resp.data
}

export async function notificationStatus(): Promise<{ channel: string; configured: boolean }> {
  const resp = await http.get<{ channel: string; configured: boolean }>('/notifications/status')
  return resp.data
}
