<template>
  <div class="language-toggle" role="group" :aria-label="t('common.language')">
    <button
      type="button"
      :class="{ active: locale === 'zh-CN' }"
      :aria-pressed="locale === 'zh-CN'"
      :title="t('common.chinese')"
      @click="setLocale('zh-CN')"
    >中</button>
    <button
      type="button"
      :class="{ active: locale === 'en-US' }"
      :aria-pressed="locale === 'en-US'"
      :title="t('common.english')"
      @click="setLocale('en-US')"
    >EN</button>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from '@/composables/useI18n';

const { locale, setLocale, t } = useI18n();
</script>
