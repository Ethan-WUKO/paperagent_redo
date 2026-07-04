<template>
  <div class="page-shell auth-shell">
    <div class="auth-panel">
      <div class="auth-brand-block">
        <div class="auth-logo">
          <img src="/logo.png" alt="Yanban Agent logo" />
        </div>
        <div class="workbench-kicker">Yanban Agent</div>
        <h1>创建账号</h1>
        <p>注册后可以构建个人知识库、处理论文，并使用科研 Agent 对话。</p>
      </div>
      <NCard class="auth-card" :bordered="false">
        <NForm :model="form" @submit.prevent="handleSubmit">
          <NFormItem label="用户名">
            <NInput v-model:value="form.username" size="large" placeholder="请输入用户名" />
          </NFormItem>
          <NFormItem label="密码">
            <NInput v-model:value="form.password" size="large" type="password" show-password-on="click" placeholder="至少 8 位密码" />
          </NFormItem>
          <NFormItem label="确认密码">
            <NInput v-model:value="form.confirmPassword" size="large" type="password" show-password-on="click" placeholder="再次输入密码" />
          </NFormItem>
          <NFormItem label="邀请码">
            <NInput v-model:value="form.inviteCode" size="large" placeholder="请输入邀请码" />
          </NFormItem>
          <NSpace vertical size="large">
            <NButton type="primary" size="large" block :loading="submitting" @click="handleSubmit">注册</NButton>
            <NButton block secondary @click="router.push('/demo')">先体验 Demo</NButton>
            <NButton block quaternary @click="router.push('/login')">已有账号？去登录</NButton>
          </NSpace>
        </NForm>
      </NCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { NButton, NCard, NForm, NFormItem, NInput, NSpace } from 'naive-ui';
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';

const router = useRouter();
const authStore = useAuthStore();
const submitting = ref(false);
const form = reactive({ username: '', password: '', confirmPassword: '', inviteCode: '' });

async function handleSubmit() {
  if (!form.username || !form.password) {
    ui.message.warning('请输入用户名和密码');
    return;
  }
  if (!form.inviteCode) {
    ui.message.warning('请输入邀请码');
    return;
  }
  if (form.password !== form.confirmPassword) {
    ui.message.warning('两次输入的密码不一致');
    return;
  }
  submitting.value = true;
  try {
    await authStore.signUp({ username: form.username, password: form.password, inviteCode: form.inviteCode });
    ui.message.success('注册成功，已自动登录');
    await router.push('/chat');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '注册失败');
  } finally {
    submitting.value = false;
  }
}
</script>
