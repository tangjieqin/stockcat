<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <div class="placeholder">
    <h1>{{ route.meta.title }}</h1>
    <p v-if="route.meta.phase" class="phase">该页面规划于 {{ route.meta.phase }}，暂未开放</p>
    <p v-else class="phase">页面建设中 —— 将在对应开发阶段（P0.2 起）实现</p>
  </div>
</template>

<style scoped>
.placeholder h1 {
  margin-bottom: 8px;
}
.phase {
  color: var(--text-tertiary);
  font-size: 13px;
}
</style>
