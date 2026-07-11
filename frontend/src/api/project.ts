import http from './http';
import type { CreateAgentPlanPayload, AgentPlanResponse, AgentMessageResponse, SendMessageRequestPayload, SendMessageResponse } from './agent';

export interface ProjectSummaryResponse {
  id: number;
  name: string;
  accessMode: 'READ_ONLY';
  createdAt: string;
}

export interface ProjectFileEntry {
  path: string;
  sizeBytes: number;
  modifiedAt: string;
  sha256: string;
}

export interface ProjectManifestResponse {
  projectId: number;
  version: string;
  files: ProjectFileEntry[];
}

export interface ProjectFileResponse extends ProjectFileEntry { content: string; }

export interface ProjectSearchHit { path: string; lineNumber: number; line: string; sha256: string; }

export interface ProjectEvidenceResponse {
  id: string;
  relativePath: string;
  hash: string;
  version: string;
  chunk: string;
  trusted: boolean;
  current: boolean;
}

export interface CreateProjectPayload {
  name: string;
  projectFolder: string;
  includeRules: string[];
  ignoreRules: string[];
}

export function listProjects() { return http.get<ProjectSummaryResponse[]>('/projects'); }
export function createProject(payload: CreateProjectPayload) { return http.post<ProjectSummaryResponse>('/projects', payload); }
export function deleteProject(projectId: number) { return http.delete<void>(`/projects/${projectId}`); }
export function getProjectManifest(projectId: number) { return http.get<ProjectManifestResponse>(`/projects/${projectId}/manifest`); }
export function readProjectFile(projectId: number, path: string) { return http.get<ProjectFileResponse>(`/projects/${projectId}/files/read`, { params: { path } }); }
export function searchProject(projectId: number, query: string) { return http.get<ProjectSearchHit[]>(`/projects/${projectId}/search`, { params: { query, maxResults: 50 } }); }
export function sendProjectMessage(projectId: number, sessionId: number, payload: SendMessageRequestPayload) {
  return http.post<SendMessageResponse>(`/projects/${projectId}/agent/sessions/${sessionId}/messages`, payload);
}
export function createProjectPlan(projectId: number, sessionId: number, payload: CreateAgentPlanPayload) {
  return http.post<AgentPlanResponse>(`/projects/${projectId}/agent/sessions/${sessionId}/plans`, payload);
}
export function listProjectEvidence(projectId: number, planId: number) {
  return http.get<ProjectEvidenceResponse[]>(`/projects/${projectId}/agent/plans/${planId}/evidence`);
}
export type { AgentPlanResponse, AgentMessageResponse };
