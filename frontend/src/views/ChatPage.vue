<template>
  <AppLayout>
    <div
      class="chat-page research-chat-page"
      :class="{
        'research-chat-page--sessions-collapsed': chatSidebarCollapsed,
        'research-chat-page--agent-collapsed': agentSidebarCollapsed,
      }"
    >
      <button
        v-if="chatSidebarCollapsed"
        type="button"
        class="chat-rail-toggle chat-rail-toggle--sessions"
        title="Show conversations"
        @click="setChatSidebarCollapsed(false)"
      >
        ☰
      </button>

      <aside class="chat-sidebar" :aria-hidden="chatSidebarCollapsed">
        <NCard size="small" class="chat-panel chat-session-panel">
          <template #header>
            <div class="panel-heading">
              <span>Recent Conversations</span>
              <small>{{ sessions.length }} sessions</small>
            </div>
          </template>
          <template #header-extra>
            <div class="chat-session-actions">
              <NButton type="primary" size="small" circle @click="handleCreateSession">+</NButton>
              <NButton secondary circle size="small" class="chat-panel-collapse" title="Hide conversations" @click="setChatSidebarCollapsed(true)">⟨</NButton>
            </div>
          </template>

          <div class="chat-sidebar__hint">会话保留模型、RAG 和工具设置。建议按任务主题拆分对话。</div>

          <NSpace vertical :size="8">
            <NEmpty v-if="sessions.length === 0" description="还没有会话" size="small" />
            <div
              v-for="session in sessions"
              :key="session.id"
              role="button"
              tabindex="0"
              class="session-item"
              :class="{ 'session-item--active': selectedSessionId === session.id }"
              @click="selectSession(session.id)"
              @keydown.enter.prevent="selectSession(session.id)"
            >
              <div class="session-item__content">
                <div class="session-item__title">{{ session.title || `会话 #${session.id}` }}</div>
                <div class="session-item__meta">最近更新 {{ formatSessionUpdatedAt(session.updatedAt || session.createdAt) }}</div>
              </div>
              <NDropdown trigger="click" :options="sessionMenuOptions" @select="(key) => handleSessionMenuSelect(key, session)">
                <NButton quaternary circle size="tiny" class="session-item__more" @click.stop>⋯</NButton>
              </NDropdown>
            </div>
          </NSpace>
        </NCard>
      </aside>

      <section class="chat-main">
        <NCard
          class="chat-panel chat-workspace-panel"
          :content-style="{ display: 'grid', gridTemplateRows: 'auto minmax(0, 1fr) auto', gap: '12px', minHeight: '0' }"
        >
          <template #header>
            <div class="chat-room-title">
              <div>
                <h2>{{ activeSessionTitle }}</h2>
                <p>Ask a research question, search literature, or describe a task.</p>
              </div>
              <div class="chat-room-tags">
                <span>Literature Review</span>
                <span>Agent Workflow</span>
                <span>Knowledge RAG</span>
              </div>
            </div>
          </template>
          <template #header-extra>
            <div class="chat-toolbar">
              <NCheckbox v-model:checked="ragDisabled">禁用知识库</NCheckbox>
              <NCheckbox v-model:checked="planMode">计划模式</NCheckbox>
              <NSelect
                v-model:value="selectedSkillId"
                style="width: 190px"
                clearable
                :options="skillOptions"
                placeholder="Skill（可选）"
              />
              <NCheckbox v-model:checked="showProcessMessages">过程</NCheckbox>
              <NButton secondary round @click="() => reloadCurrentMessages()" :disabled="!selectedSessionId">刷新</NButton>
            </div>
          </template>

          <div class="chat-intro-bar">
            <div>
              <div class="chat-intro-bar__title">Research Copilot Workspace</div>
              <div class="chat-intro-bar__desc">支持 `/literature topic 5篇 bibtex` 检索文献；后续 Agent 将在右侧展示任务拆解、工具调用和执行轨迹。</div>
            </div>
            <div class="chat-intro-bar__status"><span /> Live</div>
          </div>

          <div v-if="showDemoQuestions" class="demo-question-strip">
            <div class="demo-question-strip__head">
              <strong>Demo 示例问题</strong>
              <span>点击后会填入输入框，你可以直接发送或继续修改。</span>
            </div>
            <div class="demo-question-strip__grid">
              <button v-for="question in demoQuestions" :key="question" type="button" @click="useDemoQuestion(question)">
                {{ question }}
              </button>
            </div>
          </div>

          <div ref="messagesContainerRef" class="chat-messages">
            <div v-if="messagesLoading" class="chat-loading">Loading conversation...</div>
            <NEmpty v-else-if="filteredMessages.length === 0" description="发一条消息开始对话" class="chat-empty" />
            <div v-for="message in filteredMessages" :key="message.localId" class="message-row" :class="`message-row--${message.role}`">
              <template v-if="message.role === 'system' || message.role === 'tool'">
                <details class="process-message-card">
                  <summary class="process-message-card__summary">
                    <span>{{ message.role === 'system' ? 'System process' : 'Tool output' }}</span>
                    <span class="process-message-card__meta">展开</span>
                  </summary>
                  <div class="process-message-card__content">
                    <template v-for="(segment, index) in getMessageSegments(message.content)" :key="`${message.localId}-${index}`">
                      <pre v-if="segment.type === 'code'" class="message-code-block"><code>{{ segment.content }}</code></pre>
                      <p v-else class="message-text-block">{{ segment.content }}</p>
                    </template>
                  </div>
                </details>
              </template>

              <template v-else>
                <div class="message-avatar" :class="`message-avatar--${message.role}`">{{ message.role === 'user' ? '你' : '✦' }}</div>
                <div class="message-bubble">
                  <div class="message-role">{{ message.role === 'user' ? 'You' : 'ScholarAI' }}</div>
                  <div class="message-content">
                    <template v-if="message.role === 'assistant'">
                      <MarkdownMessage :content="message.content || '正在思考...'" />
                    </template>
                    <template v-else>
                    <template v-for="(segment, index) in getMessageSegments(message.content || '...')" :key="`${message.localId}-${index}`">
                      <pre v-if="segment.type === 'code'" class="message-code-block"><code>{{ segment.content }}</code></pre>
                      <p v-else class="message-text-block">{{ segment.content }}</p>
                    </template>
                    </template>
                  </div>
                  <NButton
                    v-if="message.navigationUrl"
                    text
                    type="primary"
                    class="message-link-button"
                    @click="goToNavigation(message.navigationUrl)"
                  >
                    打开论文修改页
                  </NButton>
                </div>
              </template>
            </div>
          </div>

          <div class="chat-composer">
            <div class="chat-composer__topline">
              <div class="chat-composer__model-picker">
                <span>Model</span>
                <NSelect
                  v-model:value="selectedModelKey"
                  size="small"
                  class="chat-model-select"
                  :options="modelOptions"
                  placeholder="选择模型"
                  @update:value="handleModelChange"
                />
              </div>
              <div class="chat-composer__quick-actions">
                <button type="button" @click="draft = '/literature polarimetric FDA-MIMO self-protection jamming 5篇 bibtex'">Search Papers</button>
                <button type="button" @click="draft = '帮我润色论文'">Polish Paper</button>
                <button type="button" @click="planMode = !planMode">{{ planMode ? 'ReAct Mode' : 'Plan Mode' }}</button>
                <button type="button" @click="showProcessMessages = !showProcessMessages">Tool Trace</button>
              </div>
            </div>
            <NInput
              v-model:value="draft"
              type="textarea"
              :autosize="{ minRows: 2, maxRows: 6 }"
              placeholder="Ask a research question or describe a task..."
              @keydown.enter.exact.prevent="handleSend"
            />
            <div class="chat-composer__footer">
              <span class="chat-hint">Enter 发送 · Shift+Enter 换行</span>
              <NButton type="primary" round :loading="sending" @click="handleSend">发送 →</NButton>
            </div>
          </div>
        </NCard>
      </section>

      <button
        v-if="agentSidebarCollapsed"
        type="button"
        class="chat-rail-toggle chat-rail-toggle--agent"
        title="Show Research Agent"
        @click="setAgentSidebarCollapsed(false)"
      >
        ✦
      </button>

      <aside class="agent-sidebar" :aria-hidden="agentSidebarCollapsed">
        <section class="agent-card agent-card--plan">
          <div class="agent-card__head">
            <div>
              <strong>Research Agent</strong>
              <span>Agent Plan</span>
            </div>
            <div class="agent-card__head-actions">
              <em>Live</em>
              <NButton secondary circle size="small" class="chat-panel-collapse" title="Hide Research Agent" @click="setAgentSidebarCollapsed(true)">⟩</NButton>
            </div>
          </div>
          <div class="agent-progress"><span :style="{ width: currentPlan ? `${planProgress}%` : (sending ? '62%' : '36%') }" /></div>
          <div class="agent-plan-list" v-if="currentPlan">
            <div
              v-for="(step, index) in currentPlan.steps"
              :key="step.id"
              class="agent-plan-step"
              :class="planStepClass(step.status)"
            >
              <i>{{ index + 1 }}</i><span>{{ step.title || step.description }}</span><small>{{ step.status }}</small>
            </div>
          </div>
          <div class="agent-plan-list" v-else>
            <div class="agent-plan-step agent-plan-step--done"><i>1</i><span>Understand request</span><small>Done</small></div>
            <div class="agent-plan-step" :class="{ 'agent-plan-step--active': sending }"><i>2</i><span>{{ planMode ? 'Plan & execute' : 'Search literature' }}</span><small>{{ sending ? 'Running' : 'Ready' }}</small></div>
            <div class="agent-plan-step"><i>3</i><span>Analyze result</span><small>Pending</small></div>
            <div class="agent-plan-step"><i>4</i><span>Draft response</span><small>Pending</small></div>
          </div>
        </section>

        <section class="agent-card">
          <div class="agent-card__head"><strong>Tools & Execution</strong><a>View all</a></div>
          <div class="tool-call-row"><span>⌕</span><div><strong>search_literature</strong><small>OpenAlex / arXiv / local cards</small></div><em>Ready</em></div>
          <div class="tool-call-row"><span>▣</span><div><strong>search_knowledge</strong><small>Private RAG retrieval</small></div><em>{{ ragDisabled ? 'Off' : 'Ready' }}</em></div>
          <div class="tool-call-row"><span>✎</span><div><strong>paper polish</strong><small>Critic / repair workflow</small></div><em>Ready</em></div>
          <div class="tool-call-row"><span>⚙</span><div><strong>skill mode</strong><small>{{ selectedSkillId || 'No skill selected' }}</small></div><em>{{ selectedSkillId ? 'On' : 'Idle' }}</em></div>
        </section>

        <section class="agent-card">
          <div class="agent-card__head"><strong>Execution Trace</strong><span>{{ messages.length }} msgs</span></div>
          <div class="execution-log">
            <div><time>Now</time><span>Workspace ready</span><em>Info</em></div>
            <div v-if="sending"><time>Now</time><span>Agent response streaming</span><em>Running</em></div>
            <div><time>RAG</time><span>{{ ragDisabled ? 'Knowledge disabled' : 'Knowledge enabled' }}</span><em>{{ ragDisabled ? 'Off' : 'Ready' }}</em></div>
          </div>
        </section>
      </aside>
    </div>

    <NModal v-model:show="renameModalVisible" preset="card" title="重命名会话" style="width: 420px" :bordered="false">
      <NSpace vertical :size="14">
        <NInput
          v-model:value="renameDraft"
          maxlength="40"
          show-count
          placeholder="输入新的会话名称"
          @keydown.enter.prevent="confirmRenameSession"
        />
        <NSpace justify="end">
          <NButton secondary @click="renameModalVisible = false">取消</NButton>
          <NButton type="primary" :loading="renaming" @click="confirmRenameSession">保存</NButton>
        </NSpace>
      </NSpace>
    </NModal>
  </AppLayout>
</template>

<script setup lang="ts">
import { NButton, NCard, NCheckbox, NDropdown, NEmpty, NInput, NModal, NSelect, NSpace } from 'naive-ui';
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import MarkdownMessage from '@/components/MarkdownMessage.vue';
import { getDemoConfig } from '@/api/demo';
import {
  createPlan,
  createSession,
  deleteSession as deleteAgentSession,
  executePlanAsync,
  getPlan,
  listMessages,
  listSessions,
  updateSession as updateAgentSession,
  type AgentMessageResponse,
  type AgentPlanResponse,
  type AgentPlanStepResponse,
  type AgentSessionResponse,
} from '@/api/agent';
import { listSkills, type SkillListItemResponse } from '@/api/skills';
import { getSettings, type UserSettingsResponse } from '@/api/settings';
import { useAuthStore } from '@/stores/auth';
import { ui } from '@/ui';

type MessageRole = 'user' | 'assistant' | 'system' | 'tool';

interface ChatMessageView {
  localId: string;
  role: MessageRole;
  content: string;
  toolCallsJson?: string | null;
  navigationUrl?: string | null;
}

interface WsChatEvent {
  type: 'chunk' | 'done' | 'error';
  content: string | null;
  sessionId: number | null;
  error: string | null;
  finishReason: string | null;
  navigationUrl: string | null;
}

interface MessageSegment {
  type: 'text' | 'code';
  content: string;
}

const router = useRouter();
const route = useRoute();
const authStore = useAuthStore();
const DEFAULT_DEMO_QUESTIONS = [
  '根据知识库，概括这个项目能解决什么问题。',
  '演示文档里的组会时间、地点和下次 DDL 是什么？',
  '这个项目的 RAG 流程包含哪些步骤？',
  '用计划模式帮我把两周内完善 Agent 能力拆成任务。',
];
const sessions = ref<AgentSessionResponse[]>([]);
const selectedSessionId = ref<number | null>(null);
const messages = ref<ChatMessageView[]>([]);
const messagesBySessionId = ref<Record<number, ChatMessageView[]>>({});
const messagesLoading = ref(false);
const draft = ref('');
const sending = ref(false);
const selectedSkillId = ref<string | null>(null);
const availableSkills = ref<SkillListItemResponse[]>([]);
const settings = ref<UserSettingsResponse | null>(null);
const demoQuestions = ref(DEFAULT_DEMO_QUESTIONS);
const selectedModelKey = ref('');
const ragDisabled = ref(false);
const planMode = ref(false);
const showProcessMessages = ref(false);
const currentPlan = ref<AgentPlanResponse | null>(null);
const currentSocket = ref<WebSocket | null>(null);
const currentAssistantMessageId = ref<string | null>(null);
const currentAssistantMessageSessionId = ref<number | null>(null);
const messagesContainerRef = ref<HTMLElement | null>(null);
const renameModalVisible = ref(false);
const renameSessionId = ref<number | null>(null);
const renameDraft = ref('');
const renaming = ref(false);
const CHAT_SIDEBAR_COLLAPSED_KEY = 'yanban.chat.sessionsCollapsed';
const AGENT_SIDEBAR_COLLAPSED_KEY = 'yanban.chat.agentCollapsed';
const chatSidebarCollapsed = ref(readStoredBoolean(CHAT_SIDEBAR_COLLAPSED_KEY, false));
const agentSidebarCollapsed = ref(readStoredBoolean(AGENT_SIDEBAR_COLLAPSED_KEY, false));
const DEFAULT_DEEPSEEK_MODEL = 'deepseek-v4-flash';
const DEFAULT_GLM_MODEL = 'glm-5.2';
let messagesRequestSeq = 0;

const sessionMenuOptions = [
  { label: '重命名', key: 'rename' },
  { label: '删除', key: 'delete' },
];

const skillOptions = computed(() => availableSkills.value
  .filter((skill) => skill.enabled)
  .map((skill) => ({ label: `${skill.name}${skill.source === 'builtin' ? '（内置）' : ''}`, value: skill.id })));

const modelOptions = computed(() => {
  const options: { label: string; value: string }[] = [];
  const deepseekModels = settings.value?.deepseekModels?.length
    ? settings.value.deepseekModels
    : [DEFAULT_DEEPSEEK_MODEL];
  for (const m of deepseekModels) {
    options.push({ label: `DeepSeek · ${m}`, value: toModelKey('deepseek', m) });
  }
  const glmModels = settings.value?.glmModels?.length
    ? settings.value.glmModels
    : [DEFAULT_GLM_MODEL];
  for (const m of glmModels) {
    options.push({ label: `GLM · ${m}`, value: toModelKey('glm', m) });
  }
  const customModels = settings.value?.customModels || [];
  for (const cm of customModels) {
    if (!cm.builtin) {
      options.push({ label: `${cm.label} · ${cm.modelName}`, value: toModelKey(cm.providerKey, cm.modelName) });
    }
  }
  if (selectedModelKey.value && !options.some((option) => option.value === selectedModelKey.value)) {
    const selected = parseModelKey(selectedModelKey.value);
    options.push({ label: `${formatProviderName(selected.provider)} · ${selected.model}`, value: selectedModelKey.value });
  }
  return options;
});

const activeSessionTitle = computed(() => {
  const active = sessions.value.find((item) => item.id === selectedSessionId.value);
  return active?.title || '研伴对话';
});

const planProgress = computed(() => {
  const steps = currentPlan.value?.steps || [];
  if (steps.length === 0) {
    return sending.value ? 62 : 36;
  }
  const finished = steps.filter((step) => ['COMPLETED', 'DEGRADED', 'SUPERSEDED', 'FAILED', 'SKIPPED'].includes(step.status)).length;
  return Math.max(8, Math.round((finished / steps.length) * 100));
});

const filteredMessages = computed(() => {
  const visibleMessages = messages.value.filter((message) => !isEmptyAssistantToolCallMessage(message));
  if (showProcessMessages.value) {
    return visibleMessages;
  }
  return visibleMessages.filter((message) => message.role === 'user' || message.role === 'assistant');
});

const isDemoExperience = computed(() => route.query.demo === '1' || Boolean(authStore.currentUser?.demo));
const showDemoQuestions = computed(() => isDemoExperience.value && !sending.value && filteredMessages.value.length === 0);

onMounted(async () => {
  applyMobileChatDefaults();
  await Promise.all([loadSettings(), loadSkills()]);
  await loadDemoConfigIfNeeded();
  await loadSessions();
  applyQuestionFromRoute();
});

onBeforeUnmount(() => {
  currentSocket.value?.close();
});

async function loadDemoConfigIfNeeded() {
  if (!isDemoExperience.value) {
    return;
  }
  try {
    const { data } = await getDemoConfig();
    if (data.exampleQuestions?.length) {
      demoQuestions.value = data.exampleQuestions;
    }
  } catch {
    demoQuestions.value = DEFAULT_DEMO_QUESTIONS;
  }
}

function applyQuestionFromRoute() {
  const queryQuestion = route.query.q;
  if (typeof queryQuestion === 'string' && queryQuestion.trim()) {
    useDemoQuestion(queryQuestion);
  }
}

function useDemoQuestion(question: string) {
  draft.value = question;
  if (question.includes('计划模式') || question.includes('拆成任务')) {
    planMode.value = true;
  }
}

function applyMobileChatDefaults() {
  if (typeof window !== 'undefined' && window.matchMedia('(max-width: 760px)').matches) {
    setChatSidebarCollapsed(true);
    setAgentSidebarCollapsed(true);
  }
}

async function loadSettings() {
  try {
    const { data } = await getSettings();
    settings.value = data;
    if (!selectedModelKey.value) {
      selectedModelKey.value = defaultModelKeyFromSettings(data);
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载模型设置失败');
    if (!selectedModelKey.value) {
      selectedModelKey.value = toModelKey('deepseek', DEFAULT_DEEPSEEK_MODEL);
    }
  }
}

async function loadSkills() {
  try {
    const { data } = await listSkills();
    availableSkills.value = data;
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载 Skills 失败');
  }
}

async function loadSessions(selectLatest = true) {
  const { data } = await listSessions();
  sessions.value = data;
  if (selectLatest && data.length > 0) {
    await selectSession(data[0].id);
  }
}

async function selectSession(sessionId: number) {
  selectedSessionId.value = sessionId;
  currentPlan.value = null;
  messages.value = messagesBySessionId.value[sessionId] || [];
  const session = sessions.value.find((item) => item.id === sessionId);
  ragDisabled.value = Boolean(session?.ragDisabled);
  if (session?.modelProvider && session?.model) {
    selectedModelKey.value = toModelKey(session.modelProvider, session.model);
  }
  await reloadCurrentMessages(sessionId);
}

async function reloadCurrentMessages(sessionId = selectedSessionId.value) {
  if (!sessionId) {
    messages.value = [];
    return;
  }
  const requestSeq = ++messagesRequestSeq;
  messagesLoading.value = selectedSessionId.value === sessionId && !messagesBySessionId.value[sessionId]?.length;
  try {
    const { data } = await listMessages(sessionId, { limit: 50, view: showProcessMessages.value ? 'all' : 'chat' });
    const nextMessages = data.map(toViewMessage);
    setSessionMessages(sessionId, nextMessages);
    if (selectedSessionId.value === sessionId && requestSeq === messagesRequestSeq) {
      await scrollMessagesToBottom();
    }
  } finally {
    if (selectedSessionId.value === sessionId && requestSeq === messagesRequestSeq) {
      messagesLoading.value = false;
    }
  }
}

function setSessionMessages(sessionId: number, nextMessages: ChatMessageView[]) {
  messagesBySessionId.value = {
    ...messagesBySessionId.value,
    [sessionId]: nextMessages,
  };
  if (selectedSessionId.value === sessionId) {
    messages.value = nextMessages;
  }
}

function appendSessionMessage(sessionId: number, message: ChatMessageView) {
  const nextMessages = [...(messagesBySessionId.value[sessionId] || []), message];
  setSessionMessages(sessionId, nextMessages);
}

function updateSessionMessage(sessionId: number, localId: string | null, updater: (message: ChatMessageView) => void) {
  if (!localId) {
    return;
  }
  const currentMessages = messagesBySessionId.value[sessionId] || [];
  const nextMessages = currentMessages.map((message) => {
    if (message.localId !== localId) {
      return message;
    }
    const copy = { ...message };
    updater(copy);
    return copy;
  });
  setSessionMessages(sessionId, nextMessages);
}

function removeSessionMessage(sessionId: number | null, localId: string | null) {
  if (!sessionId || !localId) {
    return;
  }
  const nextMessages = (messagesBySessionId.value[sessionId] || []).filter((message) => message.localId !== localId);
  setSessionMessages(sessionId, nextMessages);
}

async function handleCreateSession() {
  try {
    const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
    const { data } = await createSession({
      title: '新会话',
      ragDisabled: false,
      modelProvider: selectedModel.provider,
      model: selectedModel.model,
    });
    sessions.value = [data, ...sessions.value];
    await selectSession(data.id);
    ui.message.success('已创建新会话');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '创建会话失败');
  }
}

async function handleSend() {
  if (!draft.value.trim()) {
    ui.message.warning('请输入消息内容');
    return;
  }
  if (sending.value) {
    return;
  }

  let activeSendSessionId: number | null = null;
  try {
    sending.value = true;
    let sessionId = selectedSessionId.value;
    if (!sessionId) {
      const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
      const { data } = await createSession({
        title: '新会话',
        ragDisabled: ragDisabled.value,
        modelProvider: selectedModel.provider,
        model: selectedModel.model,
      });
      sessions.value = [data, ...sessions.value];
      selectedSessionId.value = data.id;
      sessionId = data.id;
    } else {
      await ensureActiveSessionModelSynced(sessionId);
    }
    activeSendSessionId = sessionId;

    const content = draft.value.trim();
    draft.value = '';
    appendSessionMessage(sessionId, {
      localId: `user-${Date.now()}`,
      role: 'user',
      content,
    });

    const assistantId = `assistant-${Date.now()}`;
    currentAssistantMessageId.value = assistantId;
    currentAssistantMessageSessionId.value = sessionId;
    appendSessionMessage(sessionId, {
      localId: assistantId,
      role: 'assistant',
      content: '',
      navigationUrl: null,
    });
    await scrollMessagesToBottom();

    if (planMode.value && !isPlanArtifactRequest(content)) {
      await sendPlanMessage(sessionId, content, ragDisabled.value, selectedSkillId.value);
    } else {
      await sendWsMessage(sessionId, content, ragDisabled.value, selectedSkillId.value);
    }
  } catch (error: any) {
    removePendingAssistant();
    if (activeSendSessionId) {
      await reloadCurrentMessages(activeSendSessionId).catch(() => undefined);
    }
    ui.message.error(error.response?.data?.message || error.message || '发送失败');
    sending.value = false;
  }
}

function isPlanArtifactRequest(content: string) {
  const text = content.trim().toLowerCase();
  if (!text) {
    return false;
  }
  const asksForPlanArtifact = /(制定|设计|生成|输出|给我|帮我).{0,16}(研究计划|学习计划|学习路线|路线图|roadmap|plan)/i.test(text)
    || /(研究计划|学习计划|学习路线|路线图|roadmap).{0,16}(制定|设计|生成|输出)/i.test(text);
  const asksToExecute = /(执行|开始执行|直接执行|搜索|检索|查找|调研|收集|爬取|调用|运行|分析文献|找.{0,8}论文|search|execute|run)/i.test(text);
  return asksForPlanArtifact && !asksToExecute;
}

async function sendPlanMessage(sessionId: number, content: string, disableRag: boolean, skillId: string | null) {
  currentSocket.value?.close();
  const { data: createdPlan } = await createPlan(sessionId, {
    content,
    ragDisabled: disableRag,
    skillId,
    autoExecute: false,
  });
  currentPlan.value = createdPlan;
  await setAssistantContent(buildPlanAssistantContent(createdPlan));

  let refreshing = false;
  const refreshPlan = async () => {
    if (refreshing) {
      return currentPlan.value;
    }
    refreshing = true;
    try {
      const { data } = await getPlan(createdPlan.id);
      currentPlan.value = data;
      await setAssistantContent(buildPlanAssistantContent(data));
      return data;
    } catch {
      return currentPlan.value;
    } finally {
      refreshing = false;
    }
  };

  try {
    const { data: queuedPlan } = await executePlanAsync(createdPlan.id);
    currentPlan.value = queuedPlan;
    await setAssistantContent(buildPlanAssistantContent(queuedPlan));

    await new Promise<void>((resolve) => {
      let pollTimer: number | undefined;
      let timeoutTimer: number | undefined;
      timeoutTimer = window.setTimeout(() => {
        if (pollTimer !== undefined) {
          window.clearInterval(pollTimer);
        }
        void refreshPlan().finally(resolve);
      }, 300000);
      pollTimer = window.setInterval(() => {
        void refreshPlan().then((plan) => {
          if (plan && isTerminalPlanStatus(plan.status)) {
            if (pollTimer !== undefined) {
              window.clearInterval(pollTimer);
            }
            if (timeoutTimer !== undefined) {
              window.clearTimeout(timeoutTimer);
            }
            resolve();
          }
        });
      }, 1500);
      void refreshPlan().then((plan) => {
        if (plan && isTerminalPlanStatus(plan.status)) {
          if (pollTimer !== undefined) {
            window.clearInterval(pollTimer);
          }
          if (timeoutTimer !== undefined) {
            window.clearTimeout(timeoutTimer);
          }
          resolve();
        }
      });
    });

    const finalPlan = currentPlan.value || queuedPlan;
    if (finalPlan.status === 'FAILED' || finalPlan.status === 'CANCELLED') {
      await appendAssistantChunk(`\n\nPlan ended with status ${finalPlan.status}: ${finalPlan.errorMessage || 'No error message.'}`);
    }
    await afterSendFinished(sessionId);
  } catch (error: any) {
    const message = error.response?.data?.message || error.message || 'Plan execution failed';
    await refreshPlan();
    await appendAssistantChunk(`\n\nPlan execution failed: ${message}`);
    ui.message.error(message);
    sending.value = false;
    currentAssistantMessageId.value = null;
  }
}

function isTerminalPlanStatus(status: string) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'CANCELLED';
}

async function sendWsMessage(sessionId: number, content: string, disableRag: boolean, skillId: string | null) {
  currentSocket.value?.close();

  await new Promise<void>((resolve, reject) => {
    let settled = false;
    const token = authStore.token;
    if (!token) {
      reject(new Error('未登录'));
      return;
    }

    const finishResolve = () => {
      if (settled) {
        return;
      }
      settled = true;
      resolve();
    };

    const finishReject = (error: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      reject(error);
    };

    const backendHttpBase = import.meta.env.DEV
      ? 'http://localhost:8080'
      : window.location.origin;
    const wsBase = backendHttpBase.replace(/^http/, 'ws');
    const ws = new WebSocket(`${wsBase}/api/v1/ws/chat?token=${encodeURIComponent(token)}`);
    currentSocket.value = ws;

    ws.onopen = () => {
      ws.send(JSON.stringify({
        sessionId,
        content,
        ragDisabled: disableRag,
        skillId,
      }));
    };

    ws.onmessage = async (event) => {
      const payload = JSON.parse(event.data) as WsChatEvent;
      if (payload.type === 'chunk' && payload.content) {
        appendAssistantChunk(payload.content);
        return;
      }
      if (payload.type === 'error') {
        finishReject(new Error(payload.error || 'WebSocket 对话失败'));
        ws.close();
        return;
      }
      if (payload.type === 'done') {
        if (payload.navigationUrl) {
          attachAssistantNavigation(payload.navigationUrl);
        }
        await afterSendFinished(sessionId);
        ws.close();
        finishResolve();
      }
    };

    ws.onerror = () => finishReject(new Error('WebSocket 连接失败'));
    ws.onclose = () => {
      currentSocket.value = null;
      if (!settled && sending.value) {
        finishReject(new Error('WebSocket 连接已关闭'));
      }
    };
  });
}

async function appendAssistantChunk(chunk: string) {
  const sessionId = currentAssistantMessageSessionId.value;
  if (sessionId && currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.content += chunk;
    });
    if (selectedSessionId.value === sessionId) {
      await scrollMessagesToBottom();
    }
  }
}

async function setAssistantContent(content: string) {
  const sessionId = currentAssistantMessageSessionId.value;
  if (sessionId && currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.content = content;
    });
    if (selectedSessionId.value === sessionId) {
      await scrollMessagesToBottom();
    }
  }
}

async function scrollMessagesToBottom() {
  await nextTick();
  const container = messagesContainerRef.value;
  if (!container) {
    return;
  }
  container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' });
}

function attachAssistantNavigation(navigationUrl: string) {
  const sessionId = currentAssistantMessageSessionId.value;
  if (sessionId && currentAssistantMessageId.value) {
    updateSessionMessage(sessionId, currentAssistantMessageId.value, (message) => {
      message.navigationUrl = navigationUrl;
    });
  }
}

function removePendingAssistant() {
  if (!currentAssistantMessageId.value) {
    return;
  }
  removeSessionMessage(currentAssistantMessageSessionId.value, currentAssistantMessageId.value);
  currentAssistantMessageId.value = null;
  currentAssistantMessageSessionId.value = null;
}

async function handleModelChange(value: string) {
  selectedModelKey.value = value;
  if (!selectedSessionId.value) {
    return;
  }
  try {
    const selectedModel = parseModelKey(value);
    const { data } = await updateAgentSession(selectedSessionId.value, {
      modelProvider: selectedModel.provider,
      model: selectedModel.model,
    });
    replaceSession(data);
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '切换模型失败');
  }
}

async function ensureActiveSessionModelSynced(sessionId: number) {
  const selectedModel = parseModelKey(selectedModelKey.value || defaultModelKeyFromSettings(settings.value));
  const active = sessions.value.find((item) => item.id === sessionId);
  if (active?.modelProvider === selectedModel.provider && active?.model === selectedModel.model) {
    return;
  }
  const { data } = await updateAgentSession(sessionId, {
    modelProvider: selectedModel.provider,
    model: selectedModel.model,
  });
  replaceSession(data);
}

async function handleSessionMenuSelect(key: string | number, session: AgentSessionResponse) {
  if (key === 'rename') {
    openRenameSession(session);
    return;
  }
  if (key === 'delete') {
    await handleDeleteSession(session);
  }
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

function setChatSidebarCollapsed(collapsed: boolean) {
  chatSidebarCollapsed.value = collapsed;
  setStoredBoolean(CHAT_SIDEBAR_COLLAPSED_KEY, collapsed);
}

function setAgentSidebarCollapsed(collapsed: boolean) {
  agentSidebarCollapsed.value = collapsed;
  setStoredBoolean(AGENT_SIDEBAR_COLLAPSED_KEY, collapsed);
}

function openRenameSession(session: AgentSessionResponse) {
  renameSessionId.value = session.id;
  renameDraft.value = session.title || '';
  renameModalVisible.value = true;
}

async function confirmRenameSession() {
  const title = renameDraft.value.trim();
  if (!renameSessionId.value || !title) {
    ui.message.warning('请输入会话名称');
    return;
  }
  try {
    renaming.value = true;
    const { data } = await updateAgentSession(renameSessionId.value, { title });
    replaceSession(data);
    renameModalVisible.value = false;
    ui.message.success('已重命名');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '重命名失败');
  } finally {
    renaming.value = false;
  }
}

async function handleDeleteSession(session: AgentSessionResponse) {
  if (sending.value) {
    ui.message.warning('当前正在回复，暂不能删除会话');
    return;
  }
  if (!window.confirm(`确定删除「${session.title || `会话 #${session.id}`}」吗？`)) {
    return;
  }
  try {
    await deleteAgentSession(session.id);
    sessions.value = sessions.value.filter((item) => item.id !== session.id);
    if (selectedSessionId.value === session.id) {
      const next = sessions.value[0];
      if (next) {
        await selectSession(next.id);
      } else {
        selectedSessionId.value = null;
        messages.value = [];
      }
    }
    ui.message.success('已删除会话');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '删除会话失败');
  }
}

function replaceSession(session: AgentSessionResponse) {
  const index = sessions.value.findIndex((item) => item.id === session.id);
  if (index >= 0) {
    sessions.value.splice(index, 1, session);
  } else {
    sessions.value = [session, ...sessions.value];
  }
}

async function afterSendFinished(sessionId: number) {
  sending.value = false;
  currentAssistantMessageId.value = null;
  currentAssistantMessageSessionId.value = null;
  await reloadCurrentMessages(sessionId);
  const { data } = await listSessions();
  sessions.value = data;
  const active = sessions.value.find((item) => item.id === selectedSessionId.value);
  if (selectedSessionId.value === sessionId && active?.modelProvider && active?.model) {
    selectedModelKey.value = toModelKey(active.modelProvider, active.model);
  }
}

function buildPlanAssistantContent(plan: AgentPlanResponse) {
  const lines = [
    `计划执行状态：${plan.status}`,
    plan.summary ? `摘要：${plan.summary}` : '',
    '',
    '步骤：',
    ...plan.steps.map((step) => `- [${step.status}] ${step.stepKey} ${step.title || step.description}${step.errorMessage ? `：${step.errorMessage}` : ''}`),
  ].filter(Boolean);
  if (plan.errorMessage) {
    lines.push('', `错误：${plan.errorMessage}`);
  }
  return lines.join('\n');
}

function planStepClass(status: AgentPlanStepResponse['status']) {
  return {
    'agent-plan-step--done': status === 'COMPLETED' || status === 'DEGRADED' || status === 'SUPERSEDED',
    'agent-plan-step--active': status === 'RUNNING' || status === 'REPAIRING',
    'agent-plan-step--failed': status === 'FAILED' || status === 'SKIPPED',
  };
}

function toViewMessage(message: AgentMessageResponse): ChatMessageView {
  return {
    localId: `server-${message.id}`,
    role: normalizeRole(message.role),
    content: message.content,
    toolCallsJson: message.toolCallsJson,
    navigationUrl: extractNavigationUrl(message.content),
  };
}

function isEmptyAssistantToolCallMessage(message: ChatMessageView) {
  return message.role === 'assistant'
    && Boolean(message.toolCallsJson)
    && !message.content?.trim();
}

function normalizeRole(role: string): MessageRole {
  switch ((role || '').toLowerCase()) {
    case 'assistant':
      return 'assistant';
    case 'system':
      return 'system';
    case 'tool':
      return 'tool';
    default:
      return 'user';
  }
}

function getMessageSegments(content: string): MessageSegment[] {
  const source = content || '';
  const regex = /```([\w-]+)?\n([\s\S]*?)```/g;
  const segments: MessageSegment[] = [];
  let lastIndex = 0;
  let matched: RegExpExecArray | null;

  while ((matched = regex.exec(source)) !== null) {
    const [fullMatch, , codeContent] = matched;
    const start = matched.index;
    if (start > lastIndex) {
      const text = source.slice(lastIndex, start).trim();
      if (text) {
        segments.push({ type: 'text', content: text });
      }
    }
    segments.push({ type: 'code', content: codeContent.trimEnd() });
    lastIndex = start + fullMatch.length;
  }

  if (lastIndex < source.length) {
    const text = source.slice(lastIndex).trim();
    if (text) {
      segments.push({ type: 'text', content: text });
    }
  }

  return segments.length > 0 ? segments : [{ type: 'text', content: source }];
}

function extractNavigationUrl(content: string) {
  const matched = content.match(/\/paper(?:\?[^\s]+)?/);
  return matched?.[0] || null;
}

function toModelKey(provider: string, model: string) {
  return `${provider || 'deepseek'}::${model || DEFAULT_DEEPSEEK_MODEL}`;
}

function parseModelKey(key: string) {
  const [provider, ...modelParts] = (key || '').split('::');
  const fallback = defaultModelKeyFromSettings(settings.value);
  if (!provider || modelParts.length === 0) {
    return parseModelKey(fallback === key ? toModelKey('deepseek', DEFAULT_DEEPSEEK_MODEL) : fallback);
  }
  return {
    provider,
    model: modelParts.join('::') || DEFAULT_DEEPSEEK_MODEL,
  };
}

function defaultModelKeyFromSettings(currentSettings: UserSettingsResponse | null) {
  const provider = currentSettings?.defaultProvider || 'deepseek';
  const model = provider === 'glm'
    ? (currentSettings?.glmModel || DEFAULT_GLM_MODEL)
    : (currentSettings?.deepseekModel || DEFAULT_DEEPSEEK_MODEL);
  return toModelKey(provider, model);
}

function formatProviderName(provider: string) {
  const lower = (provider || '').toLowerCase();
  if (lower === 'glm') return 'GLM';
  if (lower === 'deepseek') return 'DeepSeek';
  return provider;
}

function formatSessionUpdatedAt(value: string) {
  const updatedAt = new Date(value);
  if (Number.isNaN(updatedAt.getTime())) {
    return '未知';
  }
  const diffMs = Date.now() - updatedAt.getTime();
  const minute = 60 * 1000;
  const hour = 60 * minute;
  const day = 24 * hour;
  if (diffMs < minute) {
    return '刚刚';
  }
  if (diffMs < hour) {
    return `${Math.floor(diffMs / minute)} 分钟前`;
  }
  if (diffMs < day) {
    return `${Math.floor(diffMs / hour)} 小时前`;
  }
  if (diffMs < 7 * day) {
    return `${Math.floor(diffMs / day)} 天前`;
  }
  return updatedAt.toLocaleDateString();
}

function goToNavigation(navigationUrl: string) {
  void router.push(navigationUrl);
}
</script>
