<template>
  <div class="demo-page">
    <main class="demo-shell">
      <section class="demo-hero">
        <div class="demo-hero__copy">
          <div class="workbench-kicker">ScholarAI Demo</div>
          <h1>扫码即用的科研 Agent 演示</h1>
          <p>面试官可以用游客身份体验私有知识库问答、流式聊天、计划模式和论文工作台。演示环境会定期清理，请不要上传隐私资料。</p>
          <div class="demo-actions">
            <NButton type="primary" size="large" :loading="starting" :disabled="config?.enabled === false" @click="handleStartDemo">
              一键进入游客体验
            </NButton>
            <NButton size="large" secondary @click="router.push('/login')">已有账号登录</NButton>
          </div>
          <NAlert v-if="config && !config.enabled" type="warning" title="Demo 入口未开启">
            当前后端没有开启 DEMO_ENABLED=true。部署到云端后开启该配置即可使用一键游客体验。
          </NAlert>
          <NAlert v-else type="info" title="演示提示">
            {{ config?.notice || '演示环境会定期清理，请不要上传隐私资料。' }}
          </NAlert>
        </div>

        <NCard class="demo-preview-card" :bordered="false">
          <template #header>
            <div class="section-title">推荐体验问题</div>
          </template>
          <NSpin :show="loadingConfig">
            <div class="demo-question-list">
              <button
                v-for="question in exampleQuestions"
                :key="question"
                type="button"
                @click="startWithQuestion(question)"
              >
                {{ question }}
              </button>
            </div>
          </NSpin>
        </NCard>
      </section>

      <section class="demo-feature-grid">
        <article>
          <strong>私有知识库</strong>
          <span>预置项目说明、RAG 笔记和虚拟组会日程，可直接验证检索效果。</span>
        </article>
        <article>
          <strong>流式聊天</strong>
          <span>回答逐字返回，适合手机端快速体验，不需要等待整段生成结束。</span>
        </article>
        <article>
          <strong>计划模式</strong>
          <span>复杂目标可以拆成任务步骤，右侧保留执行轨迹和工具状态。</span>
        </article>
        <article>
          <strong>移动端适配</strong>
          <span>扫码后从 Demo 到聊天、知识库、论文、设置页面都可在手机访问。</span>
        </article>
      </section>

      <section v-if="config?.limitations?.length" class="demo-limitations">
        <span v-for="item in config.limitations" :key="item">{{ item }}</span>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NSpin } from 'naive-ui';
import { useRouter } from 'vue-router';
import { getDemoConfig, type DemoConfigResponse } from '@/api/demo';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';

const DEFAULT_QUESTIONS = [
  '根据知识库，概括这个项目能解决什么问题。',
  '演示文档里的组会时间、地点和下次 DDL 是什么？',
  '这个项目的 RAG 流程包含哪些步骤？',
  '用计划模式帮我把两周内完善 Agent 能力拆成任务。',
];

const router = useRouter();
const authStore = useAuthStore();
const config = ref<DemoConfigResponse | null>(null);
const loadingConfig = ref(false);
const starting = ref(false);
const pendingQuestion = ref<string | null>(null);

const exampleQuestions = computed(() => config.value?.exampleQuestions?.length ? config.value.exampleQuestions : DEFAULT_QUESTIONS);

onMounted(loadConfig);

async function loadConfig() {
  loadingConfig.value = true;
  try {
    const { data } = await getDemoConfig();
    config.value = data;
  } catch {
    config.value = null;
  } finally {
    loadingConfig.value = false;
  }
}

async function startWithQuestion(question: string) {
  pendingQuestion.value = question;
  await handleStartDemo();
}

async function handleStartDemo() {
  if (config.value && !config.value.enabled) {
    ui.message.warning('Demo 入口尚未开启');
    return;
  }
  starting.value = true;
  try {
    await authStore.signInDemo();
    const query: Record<string, string> = { demo: '1' };
    if (pendingQuestion.value) {
      query.q = pendingQuestion.value;
    }
    await router.push({ path: '/chat', query });
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '进入 Demo 失败');
  } finally {
    starting.value = false;
    pendingQuestion.value = null;
  }
}
</script>
