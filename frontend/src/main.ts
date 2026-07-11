import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles.css';
import { useAuthStore } from './stores/auth';
import { AUTH_EXPIRED_EVENT } from './auth/session';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
const authStore = useAuthStore();
authStore.restore();
window.addEventListener(AUTH_EXPIRED_EVENT, () => {
  authStore.clear();
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
  }
});
app.use(router);

authStore.fetchCurrentUser().finally(async () => {
  if (!authStore.isAuthenticated && router.currentRoute.value.name !== 'login') {
    await router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
  }
  app.mount('#app');
});
