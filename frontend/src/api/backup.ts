import { http } from './client'

/**
 * 数据库备份（docs/API.md §12）。
 *
 * 只做导出。恢复不在页面上提供——`pg_restore` 灌回已有库会撞主键或需要先清空 schema，
 * 一次误点就没了全部流水，必须走命令行（DATABASE.md §7）。
 */
export interface BackupFile {
  name: string
  sizeBytes: number
  /** 导出完成时刻（文件修改时间），不是发起备份的时刻 */
  createdAt: string
}

export interface BackupOverview {
  /** 绝对路径：相对路径在 IDE 与命令行下指向不同地方，页面得让人看清文件在哪 */
  dir: string
  /** 当前实际会执行的命令，便于排查「为什么导不出来」 */
  method: string
  /** 恢复命令模板（文件路径是占位符）。后端拼，因为它和导出共用同一份容器/库名配置 */
  restoreCommand: string
  usesDocker: boolean
  files: BackupFile[]
}

export async function getBackupOverview(): Promise<BackupOverview> {
  const { data } = await http.get<BackupOverview>('/backup')
  return data
}

/**
 * 触发一次导出。超时放宽到 5 分钟：后端给 pg_dump 的上限就是 5 分钟，
 * 默认的 10 秒会让前端先放弃、后端还在写文件。
 */
export async function exportBackup(): Promise<BackupFile> {
  const { data } = await http.post<BackupFile>('/backup/export', null, { timeout: 300_000 })
  return data
}

export function downloadUrl(name: string): string {
  return `/api/v1/backup/${encodeURIComponent(name)}/download`
}

export async function deleteBackup(name: string): Promise<void> {
  await http.delete(`/backup/${encodeURIComponent(name)}`)
}
