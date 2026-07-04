import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import LoginPage from '@/views/LoginPage.vue';
import RegisterPage from '@/views/RegisterPage.vue';
import DemoPage from '@/views/DemoPage.vue';
import ChatPage from '@/views/ChatPage.vue';
import SettingsPage from '@/views/SettingsPage.vue';
import KnowledgeBasePage from '@/views/KnowledgeBasePage.vue';
import KnowledgeSearchDebugPage from '@/views/KnowledgeSearchDebugPage.vue';
import PaperPage from '@/views/PaperPage.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/chat' },
    { path: '/demo', name: 'demo', component: DemoPage },
    { path: '/login', name: 'login', component: LoginPage, meta: { guestOnly: true } },
    { path: '/register', name: 'register', component: RegisterPage, meta: { guestOnly: true } },
    { path: '/chat', name: 'chat', component: ChatPage, meta: { requiresAuth: true } },
    { path: '/paper', name: 'paper', component: PaperPage, meta: { requiresAuth: true } },
    { path: '/knowledge-base', name: 'knowledge-base', component: KnowledgeBasePage, meta: { requiresAuth: true } },
    { path: '/knowledge-base/search-debug', name: 'knowledge-base-search-debug', component: KnowledgeSearchDebugPage, meta: { requiresAuth: true } },
    { path: '/settings', name: 'settings', component: SettingsPage, meta: { requiresAuth: true } },
  ],
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  if (authStore.token && !authStore.ready) {
    await authStore.fetchCurrentUser();
  }

  if (to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return '/chat';
  }
  return true;
});

export default router;
