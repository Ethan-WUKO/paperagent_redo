<template>
  <NConfigProvider :theme="naiveTheme" :theme-overrides="themeOverrides">
    <NLoadingBarProvider>
      <NDialogProvider>
        <NNotificationProvider>
          <NMessageProvider>
            <RouterView />
          </NMessageProvider>
        </NNotificationProvider>
      </NDialogProvider>
    </NLoadingBarProvider>
  </NConfigProvider>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import {
  NConfigProvider,
  NDialogProvider,
  NLoadingBarProvider,
  NMessageProvider,
  NNotificationProvider,
  darkTheme,
  lightTheme,
} from 'naive-ui';
import { RouterView } from 'vue-router';
import { useTheme } from '@/composables/useTheme';

const { isDark } = useTheme();

const naiveTheme = computed(() => (isDark.value ? darkTheme : lightTheme));

const themeOverrides = computed(() => ({
  common: {
    primaryColor: isDark.value ? '#6d5dfc' : '#5b5cf6',
    primaryColorHover: isDark.value ? '#8275ff' : '#4f46e5',
    primaryColorPressed: isDark.value ? '#5a4be7' : '#4338ca',
    primaryColorSuppl: isDark.value ? '#22d3ee' : '#2563eb',
    borderRadius: '12px',
    fontFamily: 'Geist, Inter, Arial, "PingFang SC", "Microsoft YaHei", sans-serif',
    fontFamilyMono: '"Geist Mono", ui-monospace, SFMono-Regular, Menlo, monospace',
    ...(isDark.value
      ? {
          bodyColor: '#07101d',
          cardColor: '#101827',
          modalColor: '#101827',
          popoverColor: '#101827',
          tableColor: '#101827',
          borderColor: 'rgba(148, 163, 184, 0.18)',
          dividerColor: 'rgba(148, 163, 184, 0.16)',
          textColorBase: '#eef4ff',
          textColor1: '#eef4ff',
          textColor2: '#aeb9cc',
          textColor3: '#748198',
          inputColor: '#0b1423',
          hoverColor: '#172238',
        }
      : {
          bodyColor: '#fafafa',
          cardColor: '#ffffff',
          modalColor: '#ffffff',
          popoverColor: '#ffffff',
          tableColor: '#ffffff',
          borderColor: '#ebebeb',
          dividerColor: '#ebebeb',
          textColorBase: '#171717',
          textColor1: '#171717',
          textColor2: '#4d4d4d',
          textColor3: '#8f8f8f',
          inputColor: '#ffffff',
          hoverColor: '#f2f2f2',
        }),
  },
  Card: {
    borderRadius: '18px',
  },
  Input: {
    borderRadius: '14px',
  },
  Button: {
    borderRadiusMedium: '12px',
    borderRadiusLarge: '999px',
  },
}));
</script>
