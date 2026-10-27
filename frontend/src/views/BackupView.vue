<script setup lang="ts">
/**
 * 备份页（P4 前置小件）。导出 / 下载 / 删除，恢复只给命令不在页面上做。
 *
 * 页面上最重要的一句不是「立即备份」，是恢复区那句「会覆盖整个库」——
 * 备份功能最容易出事的时刻不是导出失败，是有人在错误的时间点了恢复。
 */
import { computed, onMounted, ref } from 'vue'
import {
  deleteBackup,
  downloadUrl,
  exportBackup,
  getBackupOverview,
  type BackupFile,
  type BackupOverview,
} from '../api/backup'
import { ApiError } from '../api/client'

const overview = ref<BackupOverview | null>(null)
const loading = ref(false)
const exporting = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

const files = computed<BackupFile[]>(() => overview.value?.files ?? [])

async function load() {
  loading.value = true
  errorMsg.value = ''
  try {
    overview.value = await getBackupOverview()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

async function runExport() {
  exporting.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const file = await exportBackup()
    successMsg.value = `已生成 ${file.name}（${fmtSize(file.sizeBytes)}）`
    await load()
  } catch (e) {
    // 后端已经把「找不到 docker」「容器没起」翻译成人话了，直接显示即可
    errorMsg.value = e instanceof ApiError ? e.message : '导出失败'
  } finally {
    exporting.value = false
  }
}

async function remove(file: BackupFile) {
  if (!confirm(`确定删除 ${file.name} 吗？\n\n删除只影响这个备份文件，库里的数据不受影响。`)) return
  errorMsg.value = ''
  successMsg.value = ''
  try {
    await deleteBackup(file.name)
    await load()
  } catch (e) {
    errorMsg.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

function fmtSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function fmtTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div>
    <h1>备份</h1>
    <p class="sub">
      把整个数据库导出成 PostgreSQL 自定义格式（<code>pg_dump -Fc</code>）。
      备份文件是本项目之外的资产——即使程序哪天真跑不起来，它仍然可以用 <code>pg_restore</code> 打开。
    </p>

    <div v-if="successMsg" class="banner ok">{{ successMsg }}</div>
    <div v-if="errorMsg" class="banner error">{{ errorMsg }}</div>

    <div class="card">
      <div class="card-head">
        <div>
          <div class="card-title">导出</div>
          <div class="card-desc">点一次生成一份，不会覆盖已有备份</div>
        </div>
        <button class="btn primary" :disabled="exporting" @click="runExport">
          {{ exporting ? '导出中…' : '立即备份' }}
        </button>
      </div>

      <dl v-if="overview" class="meta">
        <dt>保存位置</dt>
        <dd class="mono">{{ overview.dir }}</dd>
        <dt>执行命令</dt>
        <dd class="mono">{{ overview.method }}</dd>
      </dl>
      <p v-if="overview?.usesDocker" class="hint">
        宿主机没有 pg_dump，借用 docker 容器里的那个。所以 <b>Docker Desktop 和
        <code>stockcat-postgres</code> 容器必须在运行</b>；容器没起会直接报错，不会生成空文件。
      </p>
      <p v-else-if="overview" class="hint">
        使用本机 pg_dump：<code>{{ overview.method.split(' ')[0] }}</code>。口令取自
        <code>.env</code> 的 <code>STOCKCAT_DB_PASSWORD</code>。
      </p>
    </div>

    <h2 class="section">备份历史</h2>
    <div v-if="files.length" class="table-wrap">
      <table class="table">
        <thead>
          <tr>
            <th>文件</th>
            <th style="width: 100px">大小</th>
            <th style="width: 180px">备份时间</th>
            <th style="width: 130px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.name">
            <td class="mono">{{ f.name }}</td>
            <td class="mono">{{ fmtSize(f.sizeBytes) }}</td>
            <td class="muted">{{ fmtTime(f.createdAt) }}</td>
            <td class="actions">
              <a class="btn small" :href="downloadUrl(f.name)">下载</a>
              <button class="btn small danger" @click="remove(f)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else-if="!loading" class="empty">
      还没有备份 —— 点「立即备份」生成第一份
    </div>

    <h2 class="section">恢复（页面不提供）</h2>
    <div class="card warn">
      <div class="card-title">⚠️ 恢复会覆盖整个数据库</div>
      <p class="warn-text">
        <code>pg_restore --clean --if-exists</code> 会先删掉现在库里的同名对象再灌入备份的内容。
        本页刻意不提供按钮：一旦点错，当前的交易流水、复盘、日记会直接变成备份那一天的样子——
        而备份通常是几天前的。请确认要退回哪个时间点，再在命令行里执行。
      </p>
      <ol class="steps">
        <li>
          先<b>再导出一份当前状态</b>（上面的「立即备份」）——万一恢复后发现有东西要回头找，
          至少有这一份兜底。
        </li>
        <li>
          关闭后端服务，避免恢复过程中有写入。
        </li>
        <li>
          在项目根目录执行下面的命令，把<b>占位符换成实际备份文件的完整路径</b>：
          <pre class="cmd">{{ overview?.restoreCommand }}</pre>
        </li>
        <li>
          重启后端，打开首页看行情、持仓、复盘是否正常。
        </li>
      </ol>
      <p class="hint">更完整的说明（含配置文件与卷的处理）见 <code>docs/DATABASE.md §7</code>。</p>
    </div>
  </div>
</template>

<style scoped>
.sub {
  color: var(--text-tertiary);
  font-size: 13px;
  margin: -8px 0 16px;
  max-width: 780px;
  line-height: 1.6;
}
.card {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  padding: 16px 20px;
  margin-bottom: 24px;
  max-width: 900px;
}
.card.warn {
  border-color: #f8b4b4;
  background: #fffbfb;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}
.card-title {
  font-weight: 700;
  font-size: 14px;
}
.card-desc {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 2px;
}
.meta {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: 6px 12px;
  margin: 14px 0 0;
  font-size: 12px;
}
.meta dt {
  color: var(--text-tertiary);
}
.meta dd {
  margin: 0;
  word-break: break-all;
}
.hint {
  font-size: 12px;
  color: var(--text-tertiary);
  margin: 10px 0 0;
  line-height: 1.7;
}
.warn-text {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 8px 0 12px;
}
.steps {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.9;
  margin: 0;
  padding-left: 20px;
}
.cmd {
  background: var(--bg-hover);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 8px 10px;
  margin: 6px 0;
  font-family: Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
.section {
  font-size: 15px;
  margin: 0 0 10px;
}
.table {
  width: 100%;
  border-collapse: collapse;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 10px;
  overflow: hidden;
  max-width: 900px;
}
.table th,
.table td {
  text-align: left;
  padding: 9px 14px;
  border-bottom: 1px solid var(--border);
  font-size: 13px;
}
.table th {
  background: var(--bg-hover);
  color: var(--text-secondary);
  font-weight: 600;
}
.actions {
  display: flex;
  gap: 6px;
}
.mono {
  font-family: Consolas, monospace;
}
.muted {
  color: var(--text-tertiary);
  font-size: 12px;
}
.empty {
  color: var(--text-tertiary);
  padding: 40px 0;
  text-align: center;
}
.banner.ok {
  background: #ecfdf5;
  color: #047857;
  border: 1px solid #a7f3d0;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
}
.banner.error {
  background: #fdf2f2;
  color: #b91c1c;
  border: 1px solid #f8b4b4;
  border-radius: 8px;
  padding: 8px 12px;
  margin: 10px 0;
  font-size: 13px;
  white-space: pre-wrap;
  line-height: 1.6;
}
.btn {
  border: 1px solid var(--border);
  background: var(--bg-panel);
  border-radius: 6px;
  padding: 5px 12px;
  font-size: 13px;
  cursor: pointer;
  color: inherit;
  text-decoration: none;
  display: inline-block;
}
.btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.btn.small {
  padding: 3px 8px;
}
.btn.danger {
  color: #b91c1c;
}
</style>
