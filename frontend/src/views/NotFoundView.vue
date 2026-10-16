<script setup lang="ts">
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="panel">
    <div class="panel-body" style="text-align: center; padding: 46px 20px">
      <h1>页面不存在</h1>
      <p class="dim">这个地址没有对应页面。可能是链接已失效，或路径写错了。</p>
      <RouterLink class="btn primary" to="/">回到总览</RouterLink>
    </div>
  </div>
</template>
