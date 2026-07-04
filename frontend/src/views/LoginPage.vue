<template>
  <div class="page-shell auth-shell">
    <div class="auth-panel">
      <div class="auth-brand-block">
        <div class="auth-logo">
          <img src="/logo.png" alt="Yanban Agent logo" />
        </div>
        <div class="workbench-kicker">Yanban Agent</div>
        <h1>欢迎回来</h1>
        <p>登录后继续使用对话、知识库、论文处理与 Skills 工作台。也可以直接进入游客 Demo 体验。</p>
      </div>
      <NCard class="auth-card" :bordered="false">
        <NForm :model="form" @submit.prevent="handleSubmit">
          <NFormItem label="用户名">
            <NInput v-model:value="form.username" size="large" placeholder="请输入用户名" />
          </NFormItem>
          <NFormItem label="密码">
            <NInput v-model:value="form.password" size="large" type="password" show-password-on="click" placeholder="请输入密码" />
          </NFormItem>
          <NSpace vertical size="large">
            <NButton type="primary" size="large" block :loading="submitting" @click="handleSubmit">登录</NButton>
            <NButton block secondary :loading="demoSubmitting" @click="handleDemoLogin">游客体验</NButton>
            <NButton block quaternary @click="router.push('/register')">没有账号？去注册</NButton>
          </NSpace>
        </NForm>
      </NCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NButton, NCard, NForm, NFormItem, NInput, NSpace } from 'naive-ui';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const submitting = ref(false);
const demoSubmitting = ref(false);
const form = reactive({ username: '', password: '' });

async function handleSubmit() {
  if (!form.username || !form.password) {
    ui.message.warning('请输入用户名和密码');
    return;
  }
  submitting.value = true;
  try {
    await authStore.signIn(form);
    ui.message.success('登录成功');
    await router.push((route.query.redirect as string) || '/chat');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '登录失败');
  } finally {
    submitting.value = false;
  }
}

async function handleDemoLogin() {
  demoSubmitting.value = true;
  try {
    await authStore.signInDemo();
    ui.message.success('已进入游客体验');
    await router.push('/chat?demo=1');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || 'Demo 入口未开启');
  } finally {
    demoSubmitting.value = false;
  }
}
</script>
