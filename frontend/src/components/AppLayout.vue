<template>
  <div
    class="app-frame"
    :class="{
      'app-frame--chat': route.path.startsWith('/chat'),
      'app-frame--paper': route.path.startsWith('/paper'),
      'app-frame--settings': route.path.startsWith('/settings'),
      'app-frame--non-chat': !route.path.startsWith('/chat'),
    }"
  >
    <aside class="app-sidebar">
      <div class="app-sidebar__brand" @click="router.push('/chat')">
        <div class="app-sidebar__logo">
          <img src="/logo.png" alt="" />
        </div>
        <div>
          <div class="app-sidebar__name">ScholarAI</div>
          <div class="app-sidebar__sub">Research Copilot</div>
        </div>
      </div>

      <div class="app-sidebar__search">
        <span>S</span>
        <span>Search workspace</span>
        <kbd>Ctrl+K</kbd>
      </div>

      <nav class="app-sidebar__nav" aria-label="主导航">
        <button
          v-for="item in navItems"
          :key="item.path"
          type="button"
          class="app-sidebar__nav-item"
          :class="{ 'app-sidebar__nav-item--active': isActiveNav(item.path) }"
          @click="router.push(item.path)"
        >
          <span>{{ item.label }}</span>
        </button>
      </nav>


      <div class="app-sidebar__spacer" />

      <div class="app-sidebar__plan">
        <div>
          <strong>AI Credits</strong>
          <span>Research workflow ready</span>
        </div>
        <div class="app-sidebar__meter"><span /></div>
      </div>

      <div class="app-sidebar__user">
        <div class="app-sidebar__avatar">{{ userInitial }}</div>
        <div class="app-sidebar__user-info">
          <strong>{{ authStore.currentUser?.username || '未登录' }}</strong>
          <span>Researcher</span>
        </div>
        <button type="button" class="app-sidebar__logout" @click="logout">Sign out</button>
      </div>
    </aside>

    <main
      class="app-workspace"
      :class="{
        'app-workspace--topbar-collapsed': showTopbar && topbarCollapsed,
        'app-workspace--no-topbar': !showTopbar,
        'app-workspace--chat': route.path.startsWith('/chat'),
        'app-workspace--paper': route.path.startsWith('/paper'),
        'app-workspace--settings': route.path.startsWith('/settings'),
      }"
    >
      <header v-if="showTopbar" class="app-topbar">
        <div>
          <h1>{{ routeTitle }}</h1>
        </div>
        <NSpace align="center" :size="10" wrap>
          <NButton secondary round @click="router.push('/chat')">+ New Task</NButton>
          <NButton secondary round @click="router.push('/paper')">Upload Paper</NButton>
          <NButton secondary round @click="router.push('/chat')">Search Literature</NButton>
          <NButton type="primary" round class="agent-mode-button">Agent Mode Live</NButton>
          <NButton quaternary circle class="theme-toggle-button" @click="toggleTheme">
            {{ isDark ? 'Light' : 'Dark' }}
          </NButton>
        </NSpace>
        <button type="button" class="app-topbar__collapse" title="Hide header" @click="setTopbarCollapsed(true)">-</button>
      </header>

      <button
        v-if="showTopbar && topbarCollapsed"
        type="button"
        class="app-topbar__restore"
        title="Show header"
        @click="setTopbarCollapsed(false)"
      >
        +
      </button>

      <section class="app-content-shell">
        <slot />
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { NButton, NSpace } from 'naive-ui';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { useTheme } from '@/composables/useTheme';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const { isDark, toggleTheme } = useTheme();
const TOPBAR_COLLAPSED_KEY = 'yanban.app.topbarCollapsed';
const topbarCollapsed = ref(readStoredBoolean(TOPBAR_COLLAPSED_KEY, false));
const showTopbar = computed(() => route.path.startsWith('/chat'));

const navItems = [
  { label: 'Workspace', path: '/chat' },
  { label: 'Papers', path: '/paper' },
  { label: 'Projects', path: '/projects' },
  { label: 'Knowledge', path: '/knowledge-base' },
  { label: 'Retrieval', path: '/knowledge-base/search-debug' },
  { label: 'Settings', path: '/settings' },
];

const userInitial = computed(() => (authStore.currentUser?.username || 'U').slice(0, 1).toUpperCase());

const routeTitle = computed(() => {
  if (route.path.startsWith('/paper')) return 'Paper Polish Workspace';
  if (route.path.startsWith('/projects')) return 'Project Workspace';
  if (route.path.startsWith('/knowledge-base/search-debug')) return 'Knowledge Search Debug';
  if (route.path.startsWith('/knowledge-base')) return 'Knowledge Base';
  if (route.path.startsWith('/settings')) return 'Settings';
  return 'Research Copilot';
});

function isActiveNav(path: string) {
  if (path === '/knowledge-base') {
    return route.path === path;
  }
  return route.path === path || route.path.startsWith(`${path}/`);
}

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

function setTopbarCollapsed(collapsed: boolean) {
  topbarCollapsed.value = collapsed;
  setStoredBoolean(TOPBAR_COLLAPSED_KEY, collapsed);
}

async function logout() {
  authStore.clear();
  await router.push('/login');
}
</script>
