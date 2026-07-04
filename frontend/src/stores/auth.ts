import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { demoLogin, login, me, register, type AuthResponse, type UserMeResponse } from '@/api/auth';

const ACCESS_TOKEN_KEY = 'yanban_access_token';
const REFRESH_TOKEN_KEY = 'yanban_refresh_token';

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(null);
  const refreshToken = ref<string | null>(null);
  const currentUser = ref<UserMeResponse | null>(null);
  const ready = ref(false);

  const isAuthenticated = computed(() => Boolean(token.value && currentUser.value));

  function restore() {
    token.value = localStorage.getItem(ACCESS_TOKEN_KEY);
    refreshToken.value = localStorage.getItem(REFRESH_TOKEN_KEY);
  }

  function persist(auth: AuthResponse) {
    token.value = auth.accessToken;
    refreshToken.value = auth.refreshToken;
    localStorage.setItem(ACCESS_TOKEN_KEY, auth.accessToken);
    localStorage.setItem(REFRESH_TOKEN_KEY, auth.refreshToken);
  }

  async function signIn(payload: { username: string; password: string }) {
    const { data } = await login(payload);
    persist(data);
    await fetchCurrentUser();
  }

  async function signInDemo() {
    const { data } = await demoLogin();
    persist(data);
    await fetchCurrentUser();
  }

  async function signUp(payload: { username: string; password: string; inviteCode?: string }) {
    const { data } = await register(payload);
    persist(data);
    await fetchCurrentUser();
  }

  async function fetchCurrentUser() {
    if (!token.value) {
      ready.value = true;
      currentUser.value = null;
      return;
    }
    try {
      const { data } = await me();
      currentUser.value = data;
    } catch {
      clear();
    } finally {
      ready.value = true;
    }
  }

  function clear() {
    token.value = null;
    refreshToken.value = null;
    currentUser.value = null;
    ready.value = true;
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
  }

  return {
    token,
    refreshToken,
    currentUser,
    ready,
    isAuthenticated,
    restore,
    signIn,
    signInDemo,
    signUp,
    fetchCurrentUser,
    clear,
  };
});
