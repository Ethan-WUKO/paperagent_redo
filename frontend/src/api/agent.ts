import http from './http';

export interface AgentSessionResponse {
  id: number;
  userId: number;
  title: string;
  modelProvider: string;
  model: string;
  maxSteps: number;
  ragDisabled: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AgentMessageResponse {
  id: number;
  sessionId: number;
  userId: number;
  role: string;
  content: string | null;
  toolCallsJson: string | null;
  paperTaskId: number | null;
  createdAt: string;
}

export function listSessions() {
  return http.get<AgentSessionResponse[]>('/agent/sessions');
}

export interface UpdateSessionPayload {
  title?: string;
  modelProvider?: string;
  model?: string;
  maxSteps?: number;
  ragDisabled?: boolean;
}

export function createSession(payload: { title?: string; modelProvider?: string; model?: string; maxSteps?: number; ragDisabled?: boolean }) {
  return http.post<AgentSessionResponse>('/agent/sessions', payload);
}

export function updateSession(sessionId: number, payload: UpdateSessionPayload) {
  return http.patch<AgentSessionResponse>(`/agent/sessions/${sessionId}`, payload);
}

export function deleteSession(sessionId: number) {
  return http.delete<void>(`/agent/sessions/${sessionId}`);
}

export interface ListMessagesParams {
  limit?: number;
  beforeId?: number;
  view?: 'chat' | 'all';
}

export function listMessages(sessionId: number, params?: ListMessagesParams) {
  return http.get<AgentMessageResponse[]>(`/agent/sessions/${sessionId}/messages`, { params });
}

export interface SendMessageRequestPayload {
  content: string;
  ragDisabled?: boolean;
  skillId?: string | null;
  clientRequestId?: string;
  experiment?: AgentExperimentRequestPayload;
}

export type AgentRuntimeMode = 'LANGCHAIN4J';
export type AgentRagMode = 'LANGCHAIN4J_AUGMENTOR';
export type AgentMemoryMode = 'CONTEXT_PACKER';
export type AgentToolCallingMode = 'LANGCHAIN4J_TOOL_BINDING';
export type AgentDebugFlag =
  | 'SHOW_RETRIEVED_CHUNKS'
  | 'SHOW_INJECTED_CONTEXT'
  | 'SHOW_TOOL_TRACE'
  | 'SHOW_MEMORY_WINDOW'
  | 'SHOW_RAW_PROMPT';

export interface AgentExperimentRequestPayload {
  enabled: boolean;
  runtimeMode?: AgentRuntimeMode;
  ragMode?: AgentRagMode;
  memoryMode?: AgentMemoryMode;
  toolCallingMode?: AgentToolCallingMode;
  debugFlags?: AgentDebugFlag[];
  persistEvalRecord?: boolean;
}

export interface AgentSelectedModesDebug {
  runtimeMode: AgentRuntimeMode;
  ragMode: AgentRagMode;
  memoryMode: AgentMemoryMode;
  toolCallingMode: AgentToolCallingMode;
}

export interface AgentRetrievedChunkDebug {
  source: string | null;
  documentId: number | null;
  filename: string | null;
  chunkIndex: number | null;
  citationId: string | null;
  score: number | null;
  content: string | null;
}

export interface AgentDebugPayload {
  selectedModes: AgentSelectedModesDebug;
  retrievedChunks: AgentRetrievedChunkDebug[];
  injectedContext: string | null;
  rawPrompt: string | null;
  debugFlags: string[];
  toolTrace: string[];
  finalCitations: string[];
  metrics: AgentExperimentMetricsDebug | null;
  memoryWindow: AgentMemoryWindowDebug | null;
  fallbacks: string[];
}

export interface AgentExperimentMetricsDebug {
  clientRequestId: string | null;
  sessionId: number | null;
  latencyMs: number | null;
  retrievedChunkCount: number | null;
  memoryWindowSize: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
  toolCallCount: number | null;
  steps: number | null;
  evalRecordId: number | null;
}

export interface AgentMemoryWindowDebug {
  mode: AgentMemoryMode;
  entries: string[];
}

export interface SendMessageResponse {
  success: boolean;
  assistantContent: string | null;
  steps: number;
  errorMessage: string | null;
  navigationUrl: string | null;
  messages: AgentMessageResponse[];
  debug: AgentDebugPayload | null;
  projectEvidence: Array<{ id: string; relativePath: string; hash: string; version: string; chunk: string; trusted: boolean; current: boolean }>;
}

export function sendMessage(sessionId: number, payload: SendMessageRequestPayload) {
  return http.post<SendMessageResponse>(`/agent/sessions/${sessionId}/messages`, payload);
}

export interface AgentPlanStepResponse {
  id: number;
  stepKey: string;
  sortOrder: number;
  title: string | null;
  description: string;
  type: string;
  dependencies: string[];
  allowedTools: string[];
  successCriteria: string | null;
  status: string;
  attemptCount: number;
  result: string | null;
  errorMessage: string | null;
  startedAt: string | null;
  finishedAt: string | null;
}

export interface AgentPlanResponse {
  id: number;
  sessionId: number;
  goal: string;
  summary: string | null;
  status: string;
  ragDisabled: boolean;
  skillId: string | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  steps: AgentPlanStepResponse[];
}

export interface AgentPlanEventResponse {
  id: number;
  planId: number;
  stepId: number | null;
  eventType: string;
  payloadJson: string | null;
  createdAt: string;
}

export interface CreateAgentPlanPayload {
  content: string;
  ragDisabled?: boolean;
  skillId?: string | null;
  autoExecute?: boolean;
}

export function createPlan(sessionId: number, payload: CreateAgentPlanPayload) {
  return http.post<AgentPlanResponse>(`/agent/sessions/${sessionId}/plans`, payload);
}

export function listPlans(sessionId: number) {
  return http.get<AgentPlanResponse[]>(`/agent/sessions/${sessionId}/plans`);
}

export function getPlan(planId: number) {
  return http.get<AgentPlanResponse>(`/agent/plans/${planId}`);
}

export function executePlan(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/execute`, {});
}

export function executePlanAsync(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/execute-async`, {});
}

export function retryPlan(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/retry`, {});
}

export function cancelPlan(planId: number) {
  return http.post<AgentPlanResponse>(`/agent/plans/${planId}/cancel`, {});
}

export function listPlanEvents(planId: number) {
  return http.get<AgentPlanEventResponse[]>(`/agent/plans/${planId}/events`);
}
