<template>
  <AppLayout>
    <main class="project-workspace">
      <div class="project-workspace__header-shell" :class="{ 'project-workspace__header-shell--collapsed': projectHeaderCollapsed }">
        <header class="project-workspace__header" :class="{ 'project-workspace__header--collapsed': projectHeaderCollapsed }">
          <h1>{{ activeProject?.name || 'Projects' }}</h1>
          <NSpace :size="8" wrap>
            <NTag v-if="activeProject" size="small" type="success">READ_ONLY</NTag>
            <NButton size="small" secondary :loading="loading.projects" @click="loadProjects">Refresh</NButton>
            <NButton v-if="activeProject" size="small" secondary type="error" :disabled="loading.send" @click="deleteModalOpen = true">Delete Project</NButton>
            <NButton size="small" type="primary" @click="createModalOpen = true">New Project</NButton>
          </NSpace>
          <button type="button" class="workspace-hero__collapse" @click="setProjectHeaderCollapsed(true)">-</button>
        </header>
        <button v-if="projectHeaderCollapsed" type="button" class="workspace-hero__restore" @click="setProjectHeaderCollapsed(false)">+</button>
      </div>

      <NAlert v-if="error" type="error" closable class="project-workspace__alert" @close="error = ''">{{ error }}</NAlert>

      <section v-if="loading.projects" class="project-workspace__state"><NSpin size="small" /> Loading Projects…</section>
      <section v-else-if="projects.length === 0" class="project-workspace__state">
        <NEmpty description="No Projects yet. Bind an existing read-only Project folder." />
      </section>

      <section v-else class="project-workspace__grid">
        <aside class="project-panel project-panel--files">
          <div class="project-panel__title"><strong>Projects</strong><span>{{ projects.length }}</span></div>
          <div class="project-list">
            <button v-for="project in projects" :key="project.id" class="project-list__item" :class="{ active: project.id === activeProjectId }" @click="selectProject(project.id)">
              <strong>{{ project.name }}</strong><small>#{{ project.id }} · {{ project.accessMode }}</small>
            </button>
          </div>
          <div class="project-panel__title project-panel__title--section">
            <strong>Files</strong>
            <NSpace class="project-panel__title-actions" :size="4" align="center">
              <span class="project-panel__count">{{ manifest?.files.length || 0 }}</span>
              <NButton size="tiny" quaternary :disabled="directoryPaths.length === 0" title="Expand all folders" @click="expandAllDirectories">Expand</NButton>
              <NButton size="tiny" quaternary :disabled="directoryPaths.length === 0" title="Collapse all folders" @click="collapseAllDirectories">Collapse</NButton>
            </NSpace>
          </div>
          <div v-if="loading.manifest" class="project-panel__loading"><NSpin size="small" /></div>
          <div v-else class="project-file-list">
            <button v-for="node in fileTree" :key="node.key" type="button" class="project-file-list__item" :class="{ 'project-file-list__directory': node.directory, active: !node.directory && selectedFile?.path === node.path }" :style="{ paddingLeft: `${6 + node.depth * 12}px` }" :title="node.path" :aria-expanded="node.directory ? !collapsedDirectories.has(node.path) : undefined" @click="node.directory ? toggleDirectory(node.path) : openFile(node.path)">
              <span><span v-if="node.directory" class="project-file-list__chevron">{{ collapsedDirectories.has(node.path) ? '▸' : '▾' }}</span>{{ node.name }}</span><small v-if="!node.directory">{{ shortHash(node.sha256) }}</small>
            </button>
            <NEmpty v-if="manifest && manifest.files.length === 0" size="small" description="No readable files" />
          </div>
          <div class="project-search">
            <NInput v-model:value="searchQuery" size="small" placeholder="Search Project" @keyup.enter="runSearch" />
            <NButton size="small" secondary :loading="loading.search" :disabled="!activeProject" @click="runSearch">Search</NButton>
          </div>
          <div v-if="searchResults.length" class="project-search-results">
            <button v-for="hit in searchResults" :key="`${hit.path}:${hit.lineNumber}`" @click="openFile(hit.path)"><strong>{{ hit.path }}:{{ hit.lineNumber }}</strong><span>{{ hit.line }}</span></button>
          </div>
          <div class="project-preview">
            <div class="project-panel__title"><strong>{{ selectedFile?.path || 'Preview' }}</strong><span v-if="selectedFile">{{ shortHash(selectedFile.sha256) }}</span></div>
            <NSpin v-if="loading.file" size="small" />
            <pre v-else-if="selectedFile">{{ selectedFile.content }}</pre>
            <span v-else>Select a readable file.</span>
          </div>
        </aside>

        <section class="project-panel project-panel--conversation">
          <div class="project-tabs"><div><button :class="{ active: centerTab === 'chat' }" @click="centerTab = 'chat'">Chat</button><button :class="{ active: centerTab === 'plan' }" @click="centerTab = 'plan'">Plan <span v-if="plans.length">{{ plans.length }}</span></button></div><NButton v-if="centerTab === 'chat'" size="tiny" quaternary :disabled="loading.send" @click="startNewConversation">New conversation</NButton></div>
          <template v-if="centerTab === 'chat'">
            <div ref="messagesContainer" class="project-messages">
              <div v-for="message in messages" :key="message.localId" class="project-message-row" :class="`project-message-row--${message.role}`">
                <details v-if="message.role === 'process'" class="project-process-card" :open="message.processOpen" @toggle="syncProcessOpen(message, $event)">
                  <summary><span>{{ processSummary(message) }}</span><span class="project-process-card__chevron">›</span></summary>
                  <pre>{{ message.content }}</pre>
                </details>
                <div v-else class="project-message" :class="`project-message--${message.role}`"><small>{{ message.role === 'user' ? 'You' : 'Project Agent' }}</small><MarkdownMessage :content="message.content || (message.pending ? 'Thinking…' : '')" :variant="message.role === 'assistant' ? 'project' : 'default'" /></div>
              </div>
              <NEmpty v-if="!loading.messages && messages.length === 0" description="Ask the Project Agent to inspect the selected Project." />
              <NSpin v-if="loading.messages" size="small" />
            </div>
            <div class="project-composer"><NInput v-model:value="chatInput" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="Ask about this read-only Project…" @keydown.ctrl.enter.prevent="sendChat" /><NButton type="primary" :loading="loading.send" :disabled="!chatInput.trim() || !activeProject" @click="sendChat">Send</NButton></div>
          </template>
          <template v-else>
            <div class="project-plan-compose"><NInput v-model:value="planInput" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }" placeholder="Create a read-only inspection plan…" /><NButton type="primary" :loading="loading.plan" :disabled="!planInput.trim() || !activeProject" @click="createPlan">Create & run</NButton></div>
            <div class="project-plans">
              <button v-for="plan in plans" :key="plan.id" class="project-plan" :class="{ active: selectedPlan?.id === plan.id }" @click="selectPlan(plan)"><div><strong>{{ plan.goal }}</strong><small>{{ plan.summary || 'No summary yet' }}</small></div><NTag size="small" :type="planTagType(plan.status)">{{ plan.status }}</NTag><ul><li v-for="step in plan.steps" :key="step.id"><span>{{ step.sortOrder }}. {{ step.title || step.stepKey }}</span><NTag size="tiny" :type="planTagType(step.status)">{{ step.status }}</NTag><p v-if="step.errorMessage">{{ step.errorMessage }}</p><p v-else-if="step.result">{{ step.result }}</p></li></ul></button>
              <NEmpty v-if="!loading.plans && plans.length === 0" description="No Project Plans in this session." />
              <NSpin v-if="loading.plans" size="small" />
            </div>
          </template>
        </section>

        <aside class="project-panel project-panel--evidence">
          <div class="project-panel__title"><strong>Agent evidence / 引用</strong><span>{{ evidence.length }}</span></div>
          <p class="project-panel__hint">Files actually read by the Agent. CURRENT means their hashes still match.</p>
          <div class="project-evidence-list">
            <article v-for="item in evidence" :key="item.id"><div><strong :title="item.relativePath">{{ item.relativePath }}</strong><NTag size="tiny" :type="item.current ? 'success' : 'warning'">{{ item.current ? 'CURRENT' : 'STALE' }}</NTag></div><dl><dt>hash</dt><dd>{{ shortHash(item.hash) }}</dd><dt>version</dt><dd>{{ shortHash(item.version) }}</dd><dt>trust</dt><dd>{{ item.trusted ? 'TRUSTED' : 'UNTRUSTED' }}</dd></dl></article>
            <NEmpty v-if="!loading.evidence && evidence.length === 0" size="small" description="Evidence appears after the Agent reads Project files or a Plan is selected." />
          </div>
          <div class="project-panel__title project-panel__title--section"><strong>Change proposals / 修改建议</strong><NSpace :size="4"><span>{{ candidates.length }}</span><NButton size="tiny" secondary :loading="loading.candidates" :disabled="!activeProject || candidates.length === 0" title="Compare each proposal's base hash with the current Project file" @click="refreshCandidates">Revalidate</NButton></NSpace></div>
          <p class="project-panel__hint">Read-only suggestions. Original Project files are never changed.</p>
          <div class="project-candidate-list"><button v-for="candidate in candidates" :key="candidate.artifactId" :class="{ active: selectedCandidate?.artifactId === candidate.artifactId }" @click="selectedCandidate = candidate"><strong :title="candidate.relativePath">{{ candidate.relativePath }}</strong><span><NTag size="tiny" type="info">{{ candidate.applicationStatus }}</NTag><NTag size="tiny" :type="candidate.status === 'STALE' ? 'warning' : 'success'">{{ candidate.status }}</NTag></span></button><NEmpty v-if="!loading.candidates && candidates.length === 0" size="small" description="Ask the Agent to modify a file to create a read-only proposal." /></div>
          <div v-if="selectedCandidate" class="project-diff"><div class="project-panel__title"><strong>Readonly diff</strong><span>{{ selectedCandidate.applicationStatus }}</span></div><p>{{ selectedCandidate.summary }}</p><pre>{{ selectedCandidate.patchOrSuggestion }}</pre></div>
        </aside>
      </section>
    </main>

    <NModal v-model:show="createModalOpen" preset="card" title="Create read-only Project" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
      <NForm label-placement="top" @submit.prevent="submitProject"><NFormItem label="Project name"><NInput v-model:value="newProject.name" placeholder="e.g. FDA-MIMO" /></NFormItem><NFormItem label="Project folder / 项目文件夹"><NInput v-model:value="newProject.projectFolder" placeholder="e.g. C:\\科研项目\\FDA-MIMO" /><small>Paste an existing absolute folder path. This is available only when the server enables controlled local Project mode; the folder must be readable and contain no symlink, junction, or reparse-point aliases.</small></NFormItem><NFormItem label="Include rules"><NInput v-model:value="newProject.includeRules" placeholder="**" /></NFormItem><NFormItem label="Ignore rules"><NInput v-model:value="newProject.ignoreRules" placeholder=".git/**, target/**" /></NFormItem><NSpace justify="end"><NButton @click="createModalOpen = false">Cancel</NButton><NButton type="primary" attr-type="submit" :loading="loading.create">Create</NButton></NSpace></NForm>
    </NModal>
    <NModal v-model:show="deleteModalOpen" preset="card" title="Delete Project binding" :mask-closable="!loading.deleteProject" :closable="!loading.deleteProject" :style="{ width: 'min(480px, calc(100vw - 32px))' }">
      <p class="project-delete-copy">This removes <strong>{{ activeProject?.name }}</strong> from Project Workspace. It never deletes or changes the local folder or any file inside it.</p>
      <NSpace justify="end"><NButton :disabled="loading.deleteProject" @click="deleteModalOpen = false">Cancel</NButton><NButton type="error" :loading="loading.deleteProject" @click="removeActiveProject">Delete binding</NButton></NSpace>
    </NModal>
  </AppLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { NAlert, NButton, NEmpty, NForm, NFormItem, NInput, NModal, NSpace, NSpin, NTag } from 'naive-ui';
import AppLayout from '@/components/AppLayout.vue';
import MarkdownMessage from '@/components/MarkdownMessage.vue';
import { createSession, listMessages, listPlans, type AgentMessageResponse, type AgentPlanResponse } from '@/api/agent';
import { getCandidateChange, listArtifacts, type CandidateChangeSet } from '@/api/artifact';
import { createProject, createProjectPlan, deleteProject, getProjectManifest, listProjectEvidence, listProjects, readProjectFile, searchProject, sendProjectMessage, type ProjectEvidenceResponse, type ProjectFileResponse, type ProjectManifestResponse, type ProjectSearchHit, type ProjectSummaryResponse } from '@/api/project';
import { useAuthStore } from '@/stores/auth';

type ProjectChatRole = 'user' | 'assistant' | 'process';
interface ProjectChatMessage {
  localId: string;
  role: ProjectChatRole;
  content: string;
  pending?: boolean;
  processOpen?: boolean;
  processDone?: boolean;
  processStartedAt?: number;
  processElapsedMs?: number;
}
interface ProjectWsChatEvent {
  type: 'ack' | 'process' | 'chunk' | 'reset' | 'replace' | 'done' | 'error' | 'debug';
  content?: string | null;
  assistantContent?: string | null;
  sessionId?: number | null;
  error?: string | null;
  clientRequestId?: string | null;
  projectEvidence?: ProjectEvidenceResponse[] | null;
  evidence?: ProjectEvidenceResponse[] | null;
}

const authStore = useAuthStore();
const projects = ref<ProjectSummaryResponse[]>([]); const activeProjectId = ref<number | null>(null); const manifest = ref<ProjectManifestResponse | null>(null); const selectedFile = ref<ProjectFileResponse | null>(null); const searchQuery = ref(''); const searchResults = ref<ProjectSearchHit[]>([]); const messages = ref<ProjectChatMessage[]>([]); const plans = ref<AgentPlanResponse[]>([]); const selectedPlan = ref<AgentPlanResponse | null>(null); const evidence = ref<ProjectEvidenceResponse[]>([]); const candidates = ref<CandidateChangeSet[]>([]); const selectedCandidate = ref<CandidateChangeSet | null>(null); const centerTab = ref<'chat' | 'plan'>('chat'); const chatInput = ref(''); const planInput = ref(''); const error = ref(''); const createModalOpen = ref(false); const deleteModalOpen = ref(false); const messagesContainer = ref<HTMLElement | null>(null); let projectEpoch = 0; let sessionFlight: Promise<number | null> | null = null; let planPoll: number | null = null; let currentSocket: WebSocket | null = null; let activeClientRequestId: string | null = null; let currentAssistantMessageId: string | null = null; let currentProcessMessageId: string | null = null;
const loading = reactive({ projects: false, manifest: false, file: false, search: false, messages: false, send: false, plans: false, plan: false, evidence: false, candidates: false, create: false, deleteProject: false });
const newProject = reactive({ name: '', projectFolder: '', includeRules: '**', ignoreRules: '.git/**, target/**, node_modules/**' });
const collapsedDirectories = ref<Set<string>>(new Set());
const collapsedDirectoriesByProject = new Map<number, Set<string>>();
const PROJECT_HEADER_COLLAPSED_KEY = 'yanban.project.headerCollapsed';
const projectHeaderCollapsed = ref(readStoredBoolean(PROJECT_HEADER_COLLAPSED_KEY, false));
const activeProject = computed(() => projects.value.find((item) => item.id === activeProjectId.value) || null);
const directoryPaths = computed(() => collectDirectoryPaths(manifest.value?.files || []));
const fileTree = computed(() => {
  const directories = new Set(directoryPaths.value);
  const rows: Array<{ key: string; name: string; path: string; sha256?: string; depth: number; directory: boolean }> = [];
  const walk = (prefix: string, depth: number) => {
    [...directories].filter((item) => item.split('/').length === depth + 1 && item.startsWith(prefix)).sort().forEach((dir) => { const parts = dir.split('/'); rows.push({ key: `dir:${dir}`, name: parts[parts.length - 1] || dir, path: dir, depth, directory: true }); if (!collapsedDirectories.value.has(dir)) walk(`${dir}/`, depth + 1); });
    (manifest.value?.files || []).filter((file) => file.path.split('/').length === depth + 1 && file.path.startsWith(prefix)).forEach((file) => { const parts = file.path.split('/'); rows.push({ key: file.path, name: parts[parts.length - 1] || file.path, path: file.path, sha256: file.sha256, depth, directory: false }); });
  }; walk('', 0); return rows;
});
const sessionKey = (projectId: number) => `yanban.project.session.${projectId}`;
function readStoredBoolean(key: string, fallback: boolean) { if (typeof window === 'undefined') return fallback; const value = window.localStorage.getItem(key); return value == null ? fallback : value === 'true'; }
function setProjectHeaderCollapsed(collapsed: boolean) { projectHeaderCollapsed.value = collapsed; if (typeof window !== 'undefined') window.localStorage.setItem(PROJECT_HEADER_COLLAPSED_KEY, String(collapsed)); }
function collectDirectoryPaths(files: ProjectManifestResponse['files']) { const directories = new Set<string>(); files.forEach((file) => file.path.split('/').slice(0, -1).forEach((_, index, parts) => directories.add(parts.slice(0, index + 1).join('/')))); return [...directories].sort(); }
function storeCollapsedDirectories() { if (activeProjectId.value) collapsedDirectoriesByProject.set(activeProjectId.value, new Set(collapsedDirectories.value)); }
function toggleDirectory(path: string) { const next = new Set(collapsedDirectories.value); if (next.has(path)) next.delete(path); else next.add(path); collapsedDirectories.value = next; storeCollapsedDirectories(); }
function expandAllDirectories() { collapsedDirectories.value = new Set(); storeCollapsedDirectories(); }
function collapseAllDirectories() { collapsedDirectories.value = new Set(directoryPaths.value); storeCollapsedDirectories(); }
function initializeDirectoryState(projectId: number, files: ProjectManifestResponse['files']) { const available = new Set(collectDirectoryPaths(files)); const stored = collapsedDirectoriesByProject.get(projectId); collapsedDirectories.value = stored ? new Set([...stored].filter((path) => available.has(path))) : new Set(available); storeCollapsedDirectories(); }
function revealFileInTree(path: string) { const parts = path.split('/').slice(0, -1); if (parts.length === 0) return; const next = new Set(collapsedDirectories.value); parts.forEach((_, index) => next.delete(parts.slice(0, index + 1).join('/'))); collapsedDirectories.value = next; storeCollapsedDirectories(); }
function apiError(value: unknown) { const item = value as { response?: { data?: { message?: string } }; message?: string }; return item.response?.data?.message || item.message || 'Request failed.'; }
function shortHash(value?: string) { return value ? `${value.slice(0, 10)}…` : '—'; }
function splitRules(value: string) { return value.split(/[\n,]/).map((item) => item.trim()).filter(Boolean); }
function planTagType(status: string): 'default' | 'success' | 'warning' | 'error' | 'info' { const value = status.toUpperCase(); if (value.includes('COMPLETED') || value.includes('VERIFIED')) return 'success'; if (value.includes('FAILED')) return 'error'; if (value.includes('PENDING') || value.includes('RUNNING')) return 'warning'; return 'default'; }
function syncProcessOpen(message: ProjectChatMessage, event: Event) { message.processOpen = (event.currentTarget as HTMLDetailsElement).open; }
function processSummary(message: ProjectChatMessage) { if (!message.processDone) return 'Project Agent is working…'; if (message.processElapsedMs != null) return `Process completed · ${(message.processElapsedMs / 1000).toFixed(1)}s`; return 'Process details'; }
function newClientRequestId() { return typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : `project-${Date.now()}-${Math.random().toString(16).slice(2)}`; }
function appendChatMessage(message: ProjectChatMessage) { messages.value = [...messages.value, message]; }
function updateChatMessage(localId: string | null, update: (message: ProjectChatMessage) => void) { if (!localId) return; const message = messages.value.find((item) => item.localId === localId); if (message) update(message); }
async function scrollMessagesToBottom() { await Promise.resolve(); window.requestAnimationFrame(() => { if (messagesContainer.value) messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight; }); }
function appendAssistantChunk(content: string) { updateChatMessage(currentAssistantMessageId, (message) => { message.content += content; message.pending = false; }); void scrollMessagesToBottom(); }
function replaceAssistantContent(content: string) { updateChatMessage(currentAssistantMessageId, (message) => { message.content = content; message.pending = false; }); void scrollMessagesToBottom(); }
function appendProcessLine(content: string) { const line = content.trim(); if (!line) return; updateChatMessage(currentProcessMessageId, (message) => { const lines = message.content.split('\n').filter(Boolean); if (lines[lines.length - 1] !== line) message.content = [...lines, line].join('\n'); message.processOpen = true; message.processDone = false; }); void scrollMessagesToBottom(); }
function finishProcess() { updateChatMessage(currentProcessMessageId, (message) => { message.processDone = true; message.processOpen = false; message.processElapsedMs = message.processStartedAt ? Date.now() - message.processStartedAt : undefined; }); }
function closeProjectSocket() { const socket = currentSocket; currentSocket = null; activeClientRequestId = null; if (socket && socket.readyState < WebSocket.CLOSING) socket.close(); }
function projectToolLabel(name: string) { if (name === 'project_manifest') return 'Inspecting the authorized Project directory manifest.'; if (name === 'project_search') return 'Searching authorized Project-relative files.'; if (name === 'project_read_file') return 'Reading an authorized Project-relative file.'; return 'Calling an authorized read-only Project tool.'; }
function parseToolNames(value: string | null) { if (!value) return [] as string[]; try { const parsed = JSON.parse(value); if (!Array.isArray(parsed)) return []; return parsed.map((item) => String(item?.function?.name || item?.name || '')).filter(Boolean); } catch { return []; } }
function toolResultLabel(content: string | null) { if (!content) return 'Project tool completed.'; try { const payload = JSON.parse(content); if (payload?.success === false) return 'Project tool failed; the Agent may retry with another authorized read operation.'; const path = payload?.relativePath; return path && path !== 'manifest' ? `Observed Project-relative path: ${path}` : 'Project tool completed.'; } catch { return 'Project tool completed.'; } }
function buildProjectMessages(serverMessages: AgentMessageResponse[]) {
  const result: ProjectChatMessage[] = [];
  const hasProcessSummary = serverMessages.some((item) => item.role?.toLowerCase() === 'process');
  let pendingProcess: string[] = [];
  let pendingIds: number[] = [];
  const flushProcess = () => { if (!pendingProcess.length) return; result.push({ localId: `process-server-${pendingIds.join('-') || result.length}`, role: 'process', content: pendingProcess.join('\n'), processOpen: false, processDone: true }); pendingProcess = []; pendingIds = []; };
  for (const item of serverMessages) {
    const role = item.role?.toLowerCase();
    if (role === 'assistant' && item.toolCallsJson) {
      if (!hasProcessSummary) { pendingIds.push(item.id); pendingProcess.push(...(parseToolNames(item.toolCallsJson).map(projectToolLabel).length ? parseToolNames(item.toolCallsJson).map(projectToolLabel) : ['Selecting an authorized read-only Project tool.'])); }
      continue;
    }
    if (role === 'tool') {
      if (!hasProcessSummary) { pendingIds.push(item.id); pendingProcess.push(toolResultLabel(item.content)); }
      continue;
    }
    if (role === 'system') continue;
    if (role === 'process') {
      flushProcess();
      result.push({ localId: `process-server-${item.id}`, role: 'process', content: item.content || '', processOpen: false, processDone: true });
      continue;
    }
    if (role === 'user' || role === 'assistant') {
      flushProcess();
      result.push({ localId: `server-${item.id}`, role, content: item.content || '' });
    }
  }
  flushProcess();
  return result;
}
function currentSessionId() { const projectId = activeProjectId.value; return projectId ? Number(localStorage.getItem(sessionKey(projectId))) || null : null; }
async function ensureSession() { if (sessionFlight) return sessionFlight; sessionFlight = ensureSessionOnce(false).finally(() => { sessionFlight = null; }); return sessionFlight; }
async function ensureSessionOnce(recovered: boolean): Promise<number | null> { const project = activeProject.value; if (!project) return null; const existing = currentSessionId(); if (existing) { try { await listMessages(existing, { limit: 1, view: 'chat' }); return existing; } catch (cause: any) { if (!recovered && cause?.response?.status === 404) { localStorage.removeItem(sessionKey(project.id)); return ensureSessionOnce(true); } throw cause; } } const response = await createSession({ title: `Project: ${project.name}`, ragDisabled: true }); if (activeProjectId.value !== project.id) return null; localStorage.setItem(sessionKey(project.id), String(response.data.id)); return response.data.id; }
async function loadProjects() { loading.projects = true; error.value = ''; try { projects.value = (await listProjects()).data; const wanted = activeProjectId.value && projects.value.some((item) => item.id === activeProjectId.value) ? activeProjectId.value : projects.value[0]?.id; if (wanted) await selectProject(wanted); } catch (cause) { error.value = apiError(cause); } finally { loading.projects = false; } }
async function selectProject(projectId: number) { closeProjectSocket(); currentAssistantMessageId = null; currentProcessMessageId = null; projectEpoch++; if (planPoll != null) { window.clearTimeout(planPoll); planPoll = null; } loading.file = false; loading.search = false; loading.send = false; activeProjectId.value = projectId; collapsedDirectories.value = new Set(collapsedDirectoriesByProject.get(projectId) || []); manifest.value = null; selectedFile.value = null; searchResults.value = []; messages.value = []; plans.value = []; evidence.value = []; candidates.value = []; selectedPlan.value = null; selectedCandidate.value = null; const epoch = projectEpoch; await Promise.all([loadManifest(epoch), loadConversation(epoch)]); }
async function loadManifest(epoch = projectEpoch) { const projectId = activeProjectId.value; if (!projectId) return; loading.manifest = true; try { const value = (await getProjectManifest(projectId)).data; if (epoch === projectEpoch && projectId === activeProjectId.value) { manifest.value = value; initializeDirectoryState(projectId, value.files); } } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } finally { if (epoch === projectEpoch) loading.manifest = false; } }
async function openFile(path: string) { const projectId = activeProjectId.value; const epoch = projectEpoch; if (!projectId) return; revealFileInTree(path); loading.file = true; try { const value = (await readProjectFile(projectId, path)).data; if (epoch === projectEpoch && projectId === activeProjectId.value) selectedFile.value = value; } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } finally { if (epoch === projectEpoch) loading.file = false; } }
async function runSearch() { const projectId = activeProjectId.value; const epoch = projectEpoch; if (!projectId || !searchQuery.value.trim()) { searchResults.value = []; return; } loading.search = true; try { const value = (await searchProject(projectId, searchQuery.value.trim())).data; if (epoch === projectEpoch && projectId === activeProjectId.value) searchResults.value = value; } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } finally { if (epoch === projectEpoch) loading.search = false; } }
async function loadConversation(epoch = projectEpoch) { try { const sessionId = await ensureSession(); if (!sessionId || epoch !== projectEpoch) return; loading.messages = true; loading.plans = true; await Promise.all([loadMessages(sessionId, epoch), loadPlans(sessionId, epoch), loadCandidates(sessionId, epoch)]); } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } finally { if (epoch === projectEpoch) { loading.messages = false; loading.plans = false; } } }
async function loadMessages(sessionId = currentSessionId(), epoch = projectEpoch) { if (!sessionId) return; const value = (await listMessages(sessionId, { limit: 100, view: 'all' })).data; if (epoch === projectEpoch) { messages.value = buildProjectMessages(value); await scrollMessagesToBottom(); } }
async function loadPlans(sessionId = currentSessionId(), epoch = projectEpoch) { if (!sessionId) return; const value = (await listPlans(sessionId)).data; if (epoch === projectEpoch) { plans.value = value; if (selectedPlan.value) selectedPlan.value = value.find((item) => item.id === selectedPlan.value?.id) || null; } }
function buildProjectWebSocketUrl(projectId: number, token: string) { const origin = window.location.origin.replace(/^http/, 'ws'); return `${origin}/api/v1/ws/projects/${projectId}/chat?token=${encodeURIComponent(token)}`; }
async function sendProjectWebSocket(projectId: number, sessionId: number, content: string, clientRequestId: string) {
  const token = authStore.token || localStorage.getItem('yanban_access_token');
  if (!token) throw new Error('Not authenticated.');
  closeProjectSocket();
  activeClientRequestId = clientRequestId;
  await new Promise<void>((resolve, reject) => {
    let settled = false;
    let acknowledged = false;
    const socket = new WebSocket(buildProjectWebSocketUrl(projectId, token));
    currentSocket = socket;
    const timeout = window.setTimeout(() => { if (!acknowledged && !settled) { settled = true; socket.close(); reject(new Error('Project streaming connection timed out.')); } }, 8000);
    const cleanup = () => { window.clearTimeout(timeout); if (currentSocket === socket) currentSocket = null; };
    const fail = (message: string) => { if (settled) return; settled = true; cleanup(); reject(new Error(message)); };
    socket.onopen = () => socket.send(JSON.stringify({ sessionId, content, ragDisabled: true, clientRequestId }));
    socket.onmessage = (event) => {
      let payload: ProjectWsChatEvent;
      try { payload = JSON.parse(event.data) as ProjectWsChatEvent; }
      catch { fail('Project streaming returned an invalid event.'); socket.close(); return; }
      if (payload.clientRequestId && payload.clientRequestId !== clientRequestId) return;
      if (payload.type === 'ack') { acknowledged = true; window.clearTimeout(timeout); return; }
      if (payload.type === 'process' && payload.content) { appendProcessLine(payload.content); return; }
      if (payload.type === 'reset') { replaceAssistantContent(''); return; }
      if (payload.type === 'replace') { replaceAssistantContent(payload.assistantContent || payload.content || ''); return; }
      if (payload.type === 'chunk' && payload.content) { appendAssistantChunk(payload.content); return; }
      if (payload.type === 'error') { fail(payload.error || 'Project Agent request failed.'); socket.close(); return; }
      if (payload.type === 'done') {
        if (payload.assistantContent != null) replaceAssistantContent(payload.assistantContent);
        const projectedEvidence = payload.projectEvidence || payload.evidence;
        if (projectedEvidence) evidence.value = projectedEvidence;
        finishProcess();
        if (!settled) { settled = true; cleanup(); resolve(); }
        socket.close();
      }
    };
    socket.onerror = () => fail(acknowledged ? 'Project streaming connection failed.' : 'Project streaming is unavailable.');
    socket.onclose = () => { cleanup(); if (!settled) fail('Project streaming connection closed before completion.'); };
  });
}
async function sendProjectHttp(projectId: number, sessionId: number, content: string, clientRequestId: string) {
  const response = (await sendProjectMessage(projectId, sessionId, { content, ragDisabled: true, clientRequestId })).data;
  evidence.value = response.projectEvidence || [];
  if (response.assistantContent != null) replaceAssistantContent(response.assistantContent);
  if (!response.success) throw new Error(response.errorMessage || 'Project Agent request failed.');
}
async function sendProjectWithFallback(projectId: number, sessionId: number, content: string, clientRequestId: string) {
  try { await sendProjectWebSocket(projectId, sessionId, content, clientRequestId); }
  catch (streamError) { appendProcessLine('Streaming connection unavailable; reconciling through the HTTP fallback.'); await sendProjectHttp(projectId, sessionId, content, clientRequestId); }
}
async function sendChat() {
  const projectId = activeProjectId.value; const content = chatInput.value.trim();
  if (!projectId || !content || loading.send) return;
  const epoch = projectEpoch; let requestId: string | null = null; loading.send = true; error.value = '';
  try {
    const sessionId = await ensureSession(); if (!sessionId || epoch !== projectEpoch) return;
    chatInput.value = '';
    requestId = newClientRequestId(); activeClientRequestId = requestId;
    currentProcessMessageId = `process-${requestId}`; currentAssistantMessageId = `assistant-${requestId}`;
    appendChatMessage({ localId: `user-${requestId}`, role: 'user', content });
    appendChatMessage({ localId: currentProcessMessageId, role: 'process', content: 'Starting authenticated read-only Project request.', processOpen: true, processDone: false, processStartedAt: Date.now() });
    appendChatMessage({ localId: currentAssistantMessageId, role: 'assistant', content: '', pending: true });
    await scrollMessagesToBottom();
    await sendProjectWithFallback(projectId, sessionId, content, requestId);
    if (epoch !== projectEpoch) return;
    await Promise.all([loadMessages(sessionId, epoch), loadCandidates(sessionId, epoch)]);
  } catch (cause) {
    if (requestId && activeClientRequestId === requestId) finishProcess();
    if (epoch === projectEpoch) {
      await loadMessages(currentSessionId(), epoch).catch(() => undefined);
      if (!messages.value.some((item) => item.role === 'assistant' && item.content)) chatInput.value = content;
      error.value = apiError(cause);
    }
  } finally {
    if (epoch === projectEpoch) loading.send = false;
    if (requestId && activeClientRequestId === requestId) { currentAssistantMessageId = null; currentProcessMessageId = null; closeProjectSocket(); }
  }
}
async function createPlan() { const projectId = activeProjectId.value; if (!projectId || !planInput.value.trim()) return; const epoch = projectEpoch; loading.plan = true; try { const sessionId = await ensureSession(); if (!sessionId || epoch !== projectEpoch) return; const response = await createProjectPlan(projectId, sessionId, { content: planInput.value.trim(), ragDisabled: true, autoExecute: true }); if (epoch !== projectEpoch) return; planInput.value = ''; selectedPlan.value = response.data; await loadPlans(sessionId, epoch); await selectPlan(response.data); centerTab.value = 'plan'; pollPlanUntilTerminal(sessionId, response.data.id, epoch, 0); } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } finally { if (epoch === projectEpoch) loading.plan = false; } }
async function selectPlan(plan: AgentPlanResponse) { selectedPlan.value = plan; if (!activeProjectId.value) return; loading.evidence = true; try { evidence.value = (await listProjectEvidence(activeProjectId.value, plan.id)).data; } catch (cause) { error.value = apiError(cause); evidence.value = []; } finally { loading.evidence = false; } }
async function loadCandidates(sessionId: number, epoch = projectEpoch) { loading.candidates = true; try { const artifacts = (await listArtifacts(sessionId)).data.filter((item) => item.sourceType === 'CANDIDATE_CHANGESET'); const details = await Promise.allSettled(artifacts.map((item) => getCandidateChange(item.id))); if (epoch !== projectEpoch) return; candidates.value = details.flatMap((item) => item.status === 'fulfilled' && item.value.data.projectId === activeProjectId.value ? [item.value.data] : []); if (selectedCandidate.value) selectedCandidate.value = candidates.value.find((item) => item.artifactId === selectedCandidate.value?.artifactId) || null; } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } finally { if (epoch === projectEpoch) loading.candidates = false; } }
function planTerminal(status: string) { return ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status.toUpperCase()); }
async function pollPlanUntilTerminal(sessionId: number, planId: number, epoch: number, attempt: number) { if (epoch !== projectEpoch) return; await loadPlans(sessionId, epoch); const plan = plans.value.find((item) => item.id === planId); if (!plan) return; await selectPlan(plan); if (planTerminal(plan.status)) { await loadCandidates(sessionId, epoch); return; } if (attempt >= 12) { error.value = 'Plan is still running; use Refresh to check its latest status.'; return; } planPoll = window.setTimeout(() => { void pollPlanUntilTerminal(sessionId, planId, epoch, attempt + 1); }, 1500); }
async function refreshCandidates() { const sessionId = currentSessionId(); if (sessionId) await loadCandidates(sessionId); }
async function startNewConversation() { const projectId = activeProjectId.value; if (!projectId || loading.send) return; closeProjectSocket(); currentAssistantMessageId = null; currentProcessMessageId = null; projectEpoch++; sessionFlight = null; localStorage.removeItem(sessionKey(projectId)); messages.value = []; plans.value = []; evidence.value = []; candidates.value = []; selectedPlan.value = null; selectedCandidate.value = null; const epoch = projectEpoch; try { await loadConversation(epoch); } catch (cause) { if (epoch === projectEpoch) error.value = apiError(cause); } }
async function removeActiveProject() {
  const projectId = activeProjectId.value; if (!projectId || loading.deleteProject) return;
  loading.deleteProject = true; error.value = '';
  try {
    await deleteProject(projectId);
    closeProjectSocket(); currentAssistantMessageId = null; currentProcessMessageId = null; projectEpoch++; sessionFlight = null;
    if (planPoll != null) { window.clearTimeout(planPoll); planPoll = null; }
    localStorage.removeItem(sessionKey(projectId)); collapsedDirectoriesByProject.delete(projectId);
    projects.value = projects.value.filter((item) => item.id !== projectId); deleteModalOpen.value = false; activeProjectId.value = null;
    manifest.value = null; selectedFile.value = null; searchResults.value = []; messages.value = []; plans.value = []; evidence.value = []; candidates.value = []; selectedPlan.value = null; selectedCandidate.value = null; collapsedDirectories.value = new Set();
    const nextProject = projects.value[0]; if (nextProject) await selectProject(nextProject.id);
  } catch (cause) { error.value = apiError(cause); }
  finally { loading.deleteProject = false; }
}
async function submitProject() { if (!newProject.name.trim() || !newProject.projectFolder.trim()) { error.value = 'Project name and an absolute Project folder are required.'; return; } loading.create = true; try { const created = (await createProject({ name: newProject.name.trim(), projectFolder: newProject.projectFolder.trim(), includeRules: splitRules(newProject.includeRules), ignoreRules: splitRules(newProject.ignoreRules) })).data; createModalOpen.value = false; newProject.name = ''; newProject.projectFolder = ''; await loadProjects(); await selectProject(created.id); } catch (cause) { error.value = apiError(cause); } finally { loading.create = false; } }
onMounted(loadProjects);
onUnmounted(() => { closeProjectSocket(); if (planPoll != null) window.clearTimeout(planPoll); projectEpoch++; });
</script>

<style scoped>
.project-workspace { height: calc(100dvh - 28px); min-height: 0; overflow: hidden; display: flex; flex-direction: column; gap: 12px; color: var(--yb-text); }
.project-workspace__header-shell{position:relative;flex:0 0 auto;min-height:0;overflow:visible}.project-workspace__header-shell--collapsed{height:0}.project-workspace__header{position:relative;min-height:48px;overflow:visible;display:flex;align-items:center;justify-content:space-between;gap:16px;padding:4px 2px 12px;border-bottom:1px solid var(--yb-border);transition:min-height 280ms cubic-bezier(.2,.8,.2,1),height 280ms cubic-bezier(.2,.8,.2,1),padding 280ms cubic-bezier(.2,.8,.2,1),opacity 220ms ease,transform 280ms cubic-bezier(.2,.8,.2,1),border-color 220ms ease}.project-workspace__header--collapsed{min-height:0;height:0;padding-top:0;padding-bottom:0;opacity:0;overflow:hidden;pointer-events:none;transform:translateY(-12px);border-color:transparent}.project-workspace__header h1{margin:0;font-size:20px;letter-spacing:0}.project-workspace__alert{margin:0}.project-workspace__state{min-height:420px;display:grid;place-items:center;color:var(--yb-text-muted)}
.project-workspace__grid{flex:1;min-height:0;display:grid;grid-template-columns:minmax(210px,.78fr) minmax(350px,1.35fr) minmax(260px,.87fr);border:1px solid var(--yb-border);border-radius:12px;background:var(--yb-bg-elevated);overflow:hidden;overscroll-behavior:none}.project-panel{min-width:0;min-height:0;padding:13px;display:flex;flex-direction:column;gap:10px}.project-panel--files,.project-panel--conversation,.project-panel--evidence{overflow:hidden}.project-panel+.project-panel{border-left:1px solid var(--yb-border)}.project-panel__title{flex:0 0 auto;display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:12px}.project-panel__title>span,.project-panel__count{color:var(--yb-text-muted);font-family:ui-monospace,monospace;font-size:10px}.project-panel__title-actions{min-width:0}.project-panel__title-actions :deep(.n-button){padding:0 5px;font-size:9px}.project-panel__title--section{padding-top:10px;border-top:1px solid var(--yb-border)}.project-panel__hint{flex:0 0 auto;margin:-5px 0 0;color:var(--yb-text-muted);font-size:10px;line-height:1.4}.project-panel__loading{padding:8px}.project-list,.project-file-list,.project-search-results,.project-evidence-list,.project-candidate-list,.project-plans,.project-messages,.project-preview pre,.project-diff pre{overflow:auto;overscroll-behavior:contain;scrollbar-gutter:stable}.project-list{flex:0 1 112px;min-height:36px;display:flex;flex-direction:column;gap:3px}.project-list__item,.project-file-list__item,.project-search-results button,.project-candidate-list button,.project-plan{width:100%;border:0;background:transparent;color:inherit;text-align:left;cursor:pointer;border-radius:7px}.project-list__item{padding:7px}.project-list__item.active,.project-file-list__item.active,.project-candidate-list button.active,.project-plan.active{background:var(--yb-sidebar-active)}.project-list__item strong,.project-list__item small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-list__item strong{font-size:12px}.project-list__item small{margin-top:2px;font-size:10px;color:var(--yb-text-muted)}.project-file-list{flex:1 1 180px;min-height:70px}.project-file-list__item{padding:5px 6px;display:flex;justify-content:space-between;gap:6px;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:10px}.project-file-list__item:hover{background:var(--yb-bg-muted)}.project-file-list__item span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-file-list__item small{flex:0 0 auto;color:var(--yb-text-muted);font-size:9px}.project-file-list__directory{font-weight:650}.project-file-list__chevron{display:inline-block;width:13px;color:var(--yb-text-muted)}.project-search{flex:0 0 auto;display:grid;grid-template-columns:1fr auto;gap:6px}.project-search-results{flex:0 1 90px;min-height:0;display:flex;flex-direction:column;gap:3px}.project-search-results button{padding:5px 6px}.project-search-results strong,.project-search-results span{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:10px}.project-search-results span{color:var(--yb-text-muted);margin-top:2px}.project-preview{flex:0 0 clamp(140px,30%,240px);min-height:120px;overflow:hidden;display:flex;flex-direction:column;gap:7px;padding-top:10px;border-top:1px solid var(--yb-border);font-size:10px;color:var(--yb-text-muted)}.project-preview pre,.project-diff pre{flex:1 1 auto;min-height:0;margin:0;max-height:none;white-space:pre-wrap;word-break:break-word;color:var(--yb-text);font:10px/1.5 ui-monospace,SFMono-Regular,Consolas,monospace}
  .project-tabs{flex:0 0 auto;display:flex;align-items:flex-start;justify-content:space-between;gap:14px;border-bottom:1px solid var(--yb-border)}.project-tabs>div{display:flex;gap:14px}.project-tabs>div>button{padding:0 0 8px;border:0;border-bottom:2px solid transparent;background:transparent;color:var(--yb-text-muted);font:600 12px inherit;cursor:pointer}.project-tabs>div>button.active{border-color:var(--yb-primary);color:var(--yb-text)}.project-tabs>div>button span{margin-left:4px;color:var(--yb-text-muted)}.project-messages{flex:1 1 auto;min-height:0;display:flex;flex-direction:column;gap:10px;padding-right:4px}.project-message-row{display:flex;width:100%}.project-message-row--user{justify-content:flex-end}.project-message-row--assistant,.project-message-row--process{justify-content:flex-start}.project-message{max-width:92%;padding:9px 10px;border:1px solid var(--yb-border);border-radius:8px;font-size:12px;line-height:1.55}.project-message--user{background:var(--yb-bg-muted)}.project-message--assistant{background:var(--yb-bg-elevated);font-size:14px;line-height:1.62}.project-message--assistant :deep(.message-markdown){line-height:1.64}.project-message--assistant :deep(.message-markdown h1),.project-message--assistant :deep(.message-markdown h2),.project-message--assistant :deep(.message-markdown h3){margin:14px 0 7px;line-height:1.32;letter-spacing:0}.project-message--assistant :deep(.message-markdown h1){font-size:18px}.project-message--assistant :deep(.message-markdown h2){padding-bottom:0;border-bottom:0;font-size:16px}.project-message--assistant :deep(.message-markdown h3){font-size:15px}.project-message--assistant :deep(.message-markdown p){margin-bottom:9px}.project-message small{display:block;margin-bottom:4px;text-transform:uppercase;font-size:9px;letter-spacing:.08em;color:var(--yb-text-muted)}.project-process-card{width:min(92%,620px);border:1px solid var(--yb-border);border-radius:8px;background:var(--yb-bg-muted);font-size:11px;color:var(--yb-text-secondary)}.project-process-card summary{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:8px 10px;cursor:pointer;list-style:none;font-weight:600}.project-process-card summary::-webkit-details-marker{display:none}.project-process-card__chevron{transition:transform 160ms ease}.project-process-card[open] .project-process-card__chevron{transform:rotate(90deg)}.project-process-card pre{margin:0;padding:0 10px 10px;white-space:pre-wrap;word-break:break-word;font:10px/1.55 ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--yb-text-secondary)}.project-delete-copy{margin:0 0 20px;color:var(--yb-text-secondary);line-height:1.6}.project-composer,.project-plan-compose{flex:0 0 auto;display:grid;grid-template-columns:1fr auto;gap:8px;align-items:end;padding-top:10px;border-top:1px solid var(--yb-border);background:var(--yb-bg-elevated);position:relative;z-index:1}.project-plan-compose{margin-bottom:2px}.project-plans{flex:1 1 auto;min-height:0;display:flex;flex-direction:column;gap:7px}.project-plan{padding:9px;border:1px solid var(--yb-border)}.project-plan>div{display:flex;justify-content:space-between;gap:8px;align-items:start}.project-plan strong,.project-plan small{display:block}.project-plan strong{font-size:12px}.project-plan small{margin-top:3px;color:var(--yb-text-muted);font-size:10px}.project-plan ul{padding:0;margin:9px 0 0;list-style:none}.project-plan li{display:grid;grid-template-columns:1fr auto;gap:5px;padding:6px 0;border-top:1px solid var(--yb-border);font-size:10px}.project-plan li p{grid-column:1/-1;margin:0;color:var(--yb-text-secondary);white-space:pre-wrap;max-height:70px;overflow:auto}.project-evidence-list{flex:1 1 45%;min-height:100px;display:flex;flex-direction:column;gap:7px}.project-evidence-list article{padding:8px;border:1px solid var(--yb-border);border-radius:7px}.project-evidence-list article>div{display:flex;justify-content:space-between;gap:6px}.project-evidence-list strong{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.project-evidence-list dl{display:grid;grid-template-columns:auto 1fr;gap:3px 7px;margin:7px 0 0;font:9px ui-monospace,SFMono-Regular,monospace}.project-evidence-list dt{color:var(--yb-text-muted)}.project-evidence-list dd{margin:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.project-candidate-list{flex:1 1 30%;min-height:90px;display:flex;flex-direction:column;gap:4px}.project-candidate-list button{padding:7px}.project-candidate-list strong{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px}.project-candidate-list span{display:flex;gap:4px;margin-top:4px}.project-diff{flex:1 1 35%;min-height:110px;overflow:hidden;display:flex;flex-direction:column;margin-top:0;padding-top:10px;border-top:1px solid var(--yb-border);font-size:11px}.project-diff p{flex:0 0 auto;margin:7px 0;color:var(--yb-text-secondary);line-height:1.45}
@media (max-width:1200px){.project-workspace__grid{grid-template-columns:220px minmax(340px,1fr) 270px}.project-panel{padding:10px}}@media (max-width:980px){.project-workspace{height:auto;min-height:calc(100dvh - 28px);overflow:visible}.project-workspace__grid{grid-template-columns:1fr;overflow:visible}.project-panel{min-height:360px}.project-panel--files,.project-panel--conversation,.project-panel--evidence{overflow:visible}.project-panel+.project-panel{border-left:0;border-top:1px solid var(--yb-border)}.project-panel--files{min-height:520px}.project-workspace__header{align-items:flex-start;flex-direction:column}.project-list{flex:none;max-height:140px}.project-file-list{flex:none;max-height:260px}.project-search-results{flex:none;max-height:120px}.project-preview{flex:none;min-height:160px;max-height:280px}.project-messages,.project-plans{min-height:320px;max-height:520px}.project-evidence-list,.project-candidate-list{flex:none;min-height:120px;max-height:280px}.project-diff{flex:none;min-height:180px;max-height:320px}}
</style>
