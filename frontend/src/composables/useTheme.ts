import { computed, ref, watch } from 'vue';

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'yanban-theme-mode';
const themeMode = ref<ThemeMode>(resolveInitialTheme());

watch(themeMode, (value) => {
  if (typeof window === 'undefined') {
    return;
  }
  window.localStorage.setItem(STORAGE_KEY, value);
  document.body.classList.remove('theme-light', 'theme-dark');
  document.body.classList.add(value === 'dark' ? 'theme-dark' : 'theme-light');
}, { immediate: true });

function resolveInitialTheme(): ThemeMode {
  if (typeof window === 'undefined') {
    return 'light';
  }
  const saved = window.localStorage.getItem(STORAGE_KEY);
  if (saved === 'light' || saved === 'dark') {
    return saved;
  }
  return 'dark';
}

export function useTheme() {
  const isDark = computed(() => themeMode.value === 'dark');

  function setTheme(mode: ThemeMode) {
    themeMode.value = mode;
  }

  function toggleTheme() {
    themeMode.value = themeMode.value === 'dark' ? 'light' : 'dark';
  }

  return {
    themeMode,
    isDark,
    setTheme,
    toggleTheme,
  };
}
