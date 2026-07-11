import http from './http';

export interface ArtifactSourceRef {
  type: string | null;
  id: string | null;
  title: string | null;
}

export interface ArtifactResponse {
  id: number;
  userId: number;
  sessionId: number | null;
  title: string;
  artifactType: string;
  content: string;
  sourceType: string;
  sourceRefs: ArtifactSourceRef[];
  status: string;
  downloadUrl: string;
  downloadFilename: string;
  downloadContentType: string;
  createdAt: string;
  updatedAt: string;
}

export interface SaveArtifactToKnowledgeResponse {
  artifactId: number;
  documentId: number;
  filename: string;
  status: string;
}

export interface CandidateChangeSet {
  projectId: number;
  relativePath: string;
  baseVersion: string;
  summary: string;
  patchOrSuggestion: string;
  evidenceRefs: string[];
  status: 'CANDIDATE' | 'STALE';
  applicationStatus: 'NOT_APPLIED';
  artifactId: number;
}

export function listArtifacts(sessionId?: number) {
  return http.get<ArtifactResponse[]>('/artifacts', { params: { sessionId, limit: 100 } });
}

export function getCandidateChange(artifactId: number) {
  return http.get<CandidateChangeSet>(`/artifacts/${artifactId}/candidate`);
}

export function getArtifact(artifactId: number) {
  return http.get<ArtifactResponse>(`/artifacts/${artifactId}`);
}

export function downloadArtifact(artifactId: number) {
  return http.get<Blob>(`/artifacts/${artifactId}/download`, {
    responseType: 'blob',
  });
}

export function saveArtifactToKnowledge(artifactId: number) {
  return http.post<SaveArtifactToKnowledgeResponse>(`/artifacts/${artifactId}/save-to-knowledge`, {});
}
