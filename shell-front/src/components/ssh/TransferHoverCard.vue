<template>
  <div class="transfer-hover-card">
    <!-- Header: summary info -->
    <div class="transfer-hover-header">
      <div class="transfer-hover-title-row">
        <span class="transfer-hover-title">
          <el-icon class="title-icon"><Upload v-if="isAllUpload" /><Top v-else /></el-icon>
          <span>{{ summaryTitle }}</span>
        </span>
        <span class="transfer-hover-badge">{{ transfers.length }} 个文件</span>
      </div>

      <div class="transfer-hover-meta-row">
        <span class="meta-item speed" title="当前传输速度">
          <el-icon><Top /></el-icon>
          <span class="meta-text">{{ formatSpeed(totalSpeed) }}</span>
        </span>
        <span class="meta-item eta" title="预计剩余时间">
          <el-icon><Timer /></el-icon>
          <span class="meta-text">{{ overallEta }}</span>
        </span>
      </div>
    </div>

    <!-- Overall Progress -->
    <div class="transfer-hover-progress">
      <el-progress
        :percentage="overallProgress?.percent || 0"
        :stroke-width="5"
        :show-text="false"
      />
      <div class="progress-details">
        <span>已传输 {{ formatSize(overallProgress?.loaded) }} / {{ formatSize(overallProgress?.total) }}</span>
        <span class="progress-percent">{{ overallProgress?.percent || 0 }}%</span>
      </div>
    </div>

    <div class="transfer-hover-divider" />

    <!-- File list -->
    <div class="transfer-hover-list-header">
      <span>正在传输文件 ({{ transfers.length }})</span>
      <span v-if="transfers.length > 5" class="list-hint">可向下滚动</span>
    </div>

    <div class="transfer-hover-list">
      <div
        v-for="task in transfers"
        :key="task.id"
        class="transfer-item"
      >
        <div class="transfer-item-top">
          <div class="transfer-item-name" :title="task.name">
            <span class="direction-badge" :class="task.direction === '下载' ? 'is-download' : 'is-upload'">
              {{ task.direction || '上传' }}
            </span>
            <span class="name-text">{{ task.name }}</span>
          </div>
          <div class="transfer-item-speed" :class="{ 'is-active': task.status === '传输中' }">
            {{ task.status === '传输中' ? formatSpeed(task.speed) : task.status }}
          </div>
        </div>

        <div class="transfer-item-bar">
          <el-progress
            :percentage="getTaskPercent(task)"
            :stroke-width="3"
            :show-text="false"
            :status="['失败', '无法恢复'].includes(task.status) ? 'exception' : task.status === '已完成' ? 'success' : undefined"
          />
        </div>

        <div class="transfer-item-bottom">
          <span class="item-size">{{ formatSize(task.loaded) }} / {{ formatSize(task.total) }}</span>
          <span class="item-eta">{{ getTaskEta(task) }}</span>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="transfer-hover-footer" @click="$emit('open-list')">
      <span>点击查看完整传输列表与历史</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Upload, Top, Timer } from '@element-plus/icons-vue'
import { formatSize, formatSpeed, getTaskEta, getOverallEta } from '@/utils/transferFormat'

const props = defineProps({
  transfers: {
    type: Array,
    default: () => []
  },
  overallProgress: {
    type: Object,
    default: () => ({ loaded: 0, total: 0, percent: 0 })
  }
})

defineEmits(['open-list'])

const isAllUpload = computed(() => {
  return props.transfers.length > 0 && props.transfers.every(t => t.direction !== '下载')
})

const summaryTitle = computed(() => {
  if (isAllUpload.value) {
    return '正在上传'
  }
  const hasUpload = props.transfers.some(t => t.direction === '上传')
  const hasDownload = props.transfers.some(t => t.direction === '下载')
  if (hasDownload && !hasUpload) {
    return '正在下载'
  }
  return '正在传输'
})

const totalSpeed = computed(() => {
  return props.transfers.reduce((sum, t) => {
    return sum + (t.status === '传输中' ? Math.max(0, Number(t.speed) || 0) : 0)
  }, 0)
})

const overallEta = computed(() => {
  return getOverallEta(props.overallProgress, totalSpeed.value)
})

function getTaskPercent(task) {
  if (task.status === '已完成') return 100
  const total = Math.max(0, Number(task.total) || 0)
  const loaded = Math.max(0, Number(task.loaded) || 0)
  if (total <= 0) return 0
  return Math.min(99, Math.floor((loaded / total) * 100))
}
</script>

<style scoped>
.transfer-hover-card {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-primary, #303133);
  user-select: none;
}

.transfer-hover-header {
  margin-bottom: 8px;
}

.transfer-hover-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.transfer-hover-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary, #303133);
}

.title-icon {
  color: var(--el-color-primary, #409eff);
  font-size: 14px;
}

.transfer-hover-badge {
  background: var(--el-color-primary-light-9, #ecf5ff);
  color: var(--el-color-primary, #409eff);
  border: 1px solid var(--el-color-primary-light-7, #d9ecff);
  border-radius: 10px;
  padding: 1px 7px;
  font-size: 11px;
  font-weight: 500;
}

.transfer-hover-meta-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: var(--el-fill-color-light, #f5f7fa);
  padding: 6px 10px;
  border-radius: 6px;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-family: var(--font-mono, monospace);
}

.meta-item.speed {
  color: var(--el-color-success, #67c23a);
  font-weight: 600;
}

.meta-item.eta {
  color: var(--el-color-warning-dark-2, #b88230);
  font-weight: 500;
}

.transfer-hover-progress {
  margin-top: 8px;
}

.progress-details {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
}

.progress-percent {
  font-weight: 600;
  color: var(--el-color-primary, #409eff);
}

.transfer-hover-divider {
  height: 1px;
  background: var(--el-border-color-lighter, #ebeef5);
  margin: 10px 0 8px;
}

.transfer-hover-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
  margin-bottom: 6px;
  padding: 0 2px;
}

.list-hint {
  font-size: 10px;
  color: var(--el-text-color-placeholder, #a8abb2);
}

.transfer-hover-list {
  max-height: 200px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 2px;
}

.transfer-hover-list::-webkit-scrollbar {
  width: 4px;
}
.transfer-hover-list::-webkit-scrollbar-thumb {
  background: var(--el-border-color-light, #e4e7ed);
  border-radius: 2px;
}

.transfer-item {
  background: var(--el-fill-color-blank, #ffffff);
  border: 1px solid var(--el-border-color-lighter, #ebeef5);
  border-radius: 6px;
  padding: 7px 9px;
  transition: border-color 0.15s ease;
}

.transfer-item:hover {
  border-color: var(--el-color-primary-light-5, #a0cfff);
}

.transfer-item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.transfer-item-name {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1;
}

.direction-badge {
  flex-shrink: 0;
  font-size: 10px;
  padding: 0 4px;
  line-height: 16px;
  border-radius: 3px;
  font-weight: 500;
}

.direction-badge.is-upload {
  background: rgba(34, 197, 94, 0.12);
  color: #16a34a;
}

.direction-badge.is-download {
  background: rgba(59, 130, 246, 0.12);
  color: #2563eb;
}

.name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  font-weight: 500;
  color: var(--el-text-color-primary, #303133);
}

.transfer-item-speed {
  flex-shrink: 0;
  font-size: 11px;
  font-family: var(--font-mono, monospace);
  color: var(--el-text-color-secondary, #909399);
}

.transfer-item-speed.is-active {
  color: var(--el-color-success, #67c23a);
  font-weight: 600;
}

.transfer-item-bar {
  margin-bottom: 3px;
}

.transfer-item-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
}

.item-eta {
  color: var(--el-color-warning-dark-2, #b88230);
}

.transfer-hover-footer {
  margin-top: 8px;
  padding-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter, #ebeef5);
  text-align: center;
  font-size: 11px;
  color: var(--el-color-primary, #409eff);
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.transfer-hover-footer:hover {
  opacity: 0.8;
  text-decoration: underline;
}

:root[data-theme='dark'] .transfer-hover-badge,
html.dark .transfer-hover-badge {
  background: rgba(64, 158, 255, 0.18);
  border-color: rgba(64, 158, 255, 0.35);
}

:root[data-theme='dark'] .transfer-hover-meta-row,
html.dark .transfer-hover-meta-row {
  background: rgba(255, 255, 255, 0.05);
}

:root[data-theme='dark'] .transfer-item,
html.dark .transfer-item {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.08);
}

:root[data-theme='dark'] .transfer-item:hover,
html.dark .transfer-item:hover {
  border-color: var(--el-color-primary, #409eff);
}
</style>
