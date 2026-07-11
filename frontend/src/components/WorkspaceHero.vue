<template>
  <div class="workspace-hero-shell" :class="{ 'workspace-hero-shell--collapsed': collapsed }">
    <section
      class="workbench-hero scholar-page-hero workspace-hero"
      :class="[heroClass, { 'workspace-hero--collapsed': collapsed }]"
    >
      <div class="workspace-hero__copy">
        <h1>{{ title }}</h1>
      </div>
      <div class="workspace-hero__actions">
        <slot name="actions" />
      </div>
      <button
        type="button"
        class="workspace-hero__collapse"
        :title="collapseTitle"
        @click="setCollapsed(true)"
      >
        -
      </button>
    </section>

    <button
      v-if="collapsed"
      type="button"
      class="workspace-hero__restore"
      :title="restoreTitle"
      @click="setCollapsed(false)"
    >
      +
    </button>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';

const props = withDefaults(defineProps<{
  kicker?: string;
  title: string;
  subtitle?: string;
  storageKey: string;
  heroClass?: string | string[] | Record<string, boolean>;
  collapseTitle?: string;
  restoreTitle?: string;
}>(), {
  subtitle: '',
  heroClass: '',
  collapseTitle: 'Hide section',
  restoreTitle: 'Show section',
});

const collapsed = ref(readStoredBoolean(props.storageKey, false));

function readStoredBoolean(key: string, fallback: boolean) {
  if (typeof window === 'undefined') {
    return fallback;
  }
  const value = window.localStorage.getItem(key);
  if (value == null) {
    return fallback;
  }
  return value === 'true';
}

function setStoredBoolean(key: string, value: boolean) {
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(key, String(value));
  }
}

function setCollapsed(value: boolean) {
  collapsed.value = value;
  setStoredBoolean(props.storageKey, value);
}
</script>
