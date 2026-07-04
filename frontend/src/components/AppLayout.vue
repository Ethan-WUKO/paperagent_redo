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
        <span>⌕</span>
        <span>Search workspace</span>
        <kbd>⌘K</kbd>
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
          <span class="app-sidebar__nav-icon">{{ item.icon }}</span>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="app-sidebar__section">
        <div class="app-sidebar__section-title">
          <span>Quick Actions</span>
        </div>
        <button type="button" class="app-sidebar__mini-card" @click="router.push('/paper')">
          <strong>Polish Paper</strong>
          <small>LaTeX workflow & review</small>
        </button>
        <button type="button" class="app-sidebar__mini-card" @click="router.push('/chat')">
          <strong>Search Literature</strong>
          <small>/literature topic bibtex</small>
        </button>
      </div>

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
        <button type="button" class="app-sidebar__logout" @click="logout">Sign out ↗</button>
      </div>
    </aside>

    <main
      class="app-workspace"
      :class="{
        'app-workspace--topbar-collapsed': topbarCollapsed,
        'app-workspace--chat': route.path.startsWith('/chat'),
        'app-workspace--paper': route.path.startsWith('/paper'),
        'app-workspace--settings': route.path.startsWith('/settings'),
      }"
    >
      <header class="app-topbar">
        <div>
          <div class="app-topbar__eyebrow">{{ routeEyebrow }}</div>
          <h1>{{ routeTitle }}</h1>
          <p>{{ routeSubtitle }}</p>
        </div>
        <NSpace align="center" :size="10" wrap>
          <NButton secondary round @click="router.push('/chat')">+ New Task</NButton>
          <NButton secondary round @click="router.push('/paper')">Upload Paper</NButton>
          <NButton secondary round @click="router.push('/chat')">Search Literature</NButton>
          <NButton type="primary" round class="agent-mode-button">Agent Mode · Live</NButton>
          <NButton quaternary circle class="theme-toggle-button" @click="toggleTheme">
            {{ isDark ? '☀' : '☾' }}
          </NButton>
        </NSpace>
        <button type="button" class="app-topbar__collapse" title="Hide header" @click="setTopbarCollapsed(true)">⌃</button>
      </header>

      <button
        v-if="topbarCollapsed"
        type="button"
        class="app-topbar__restore"
        title="Show header"
        @click="setTopbarCollapsed(false)"
      >
        ⌄
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

const navItems = [
  { label: 'Workspace', path: '/chat', icon: '⌂' },
  { label: 'Paper Polish', path: '/paper', icon: '✎' },
  { label: 'Knowledge Base', path: '/knowledge-base', icon: '▣' },
  { label: 'Search Debug', path: '/knowledge-base/search-debug', icon: '⌕' },
  { label: 'Settings', path: '/settings', icon: '⚙' },
];

const userInitial = computed(() => (authStore.currentUser?.username || 'U').slice(0, 1).toUpperCase());

const routeTitle = computed(() => {
  if (route.path.startsWith('/paper')) return 'Paper Polish Workspace';
  if (route.path.startsWith('/knowledge-base/search-debug')) return 'Knowledge Search Debug';
  if (route.path.startsWith('/knowledge-base')) return 'Knowledge Base';
  if (route.path.startsWith('/settings')) return 'Settings';
  return 'Research Copilot';
});

const routeSubtitle = computed(() => {
  if (route.path.startsWith('/paper')) return 'Polish LaTeX papers, retrieve literature, review suggestions, and export artifacts.';
  if (route.path.startsWith('/knowledge-base')) return 'Manage private knowledge and verify retrieval quality.';
  if (route.path.startsWith('/settings')) return 'Configure models, tools, MCP, skills, and provider credentials.';
  return 'Chat, search literature, and run multi-step research workflows.';
});

const routeEyebrow = computed(() => {
  if (route.path.startsWith('/paper')) return 'Academic Writing';
  if (route.path.startsWith('/knowledge-base')) return 'Retrieval';
  if (route.path.startsWith('/settings')) return 'System';
  return 'AI Research Assistant';
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
