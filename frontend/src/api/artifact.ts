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

export type CandidateGovernanceStatus = 'DRAFT' | 'VALIDATED' | 'INVALID' | 'STALE';
export type CandidateReviewState = CandidateGovernanceStatus | 'ERROR';
export type CandidateChangeType = 'ADD' | 'MODIFY' | 'DELETE';

export interface CandidateSourceRange {
  startLine: number;
  endLine: number;
}

export interface CandidateEvidenceRef {
  projectVersion: string;
  relativePath: string;
  fileHash: string;
  range: CandidateSourceRange;
  parserVersion: string;
  trustLabel: string;
}

export interface CandidateTextPayload {
  text: string;
  utf8Bytes: number;
  contentHash: string;
}

export interface CandidateFileChange {
  type: CandidateChangeType;
  projectVersion: string;
  relativePath: string;
  baseFileHash: string | null;
  resultFileHash: string | null;
  candidateText: CandidateTextPayload | null;
  evidenceRefs: CandidateEvidenceRef[];
}

export interface CandidateReviewDiffEntry {
  type: CandidateChangeType;
  relativePath: string;
  baseFileHash: string | null;
  resultFileHash: string | null;
  replacementText: string | null;
}

export interface CandidateReviewDiff {
  format: 'FULL_TEXT_REPLACEMENT_V1';
  sourceCandidateFingerprint: string;
  projectVersion: string;
  entries: CandidateReviewDiffEntry[];
}

export interface CandidateValidationCheck {
  area: 'STRUCTURE' | 'VERSION' | 'EVIDENCE' | 'CONTENT_HASH' | 'BUDGET';
  status: 'PASSED' | 'FAILED' | 'SKIPPED';
}

export interface CandidateValidationIssue {
  area: CandidateValidationCheck['area'];
  code: string;
  relativePath: string | null;
}

export interface CandidateValidationUsage {
  requestedChanges: number;
  inspectedChanges: number;
  requestedEvidenceRefs: number;
  inspectedEvidenceRefs: number;
  requestedCandidateUtf8Bytes: number;
  inspectedCandidateUtf8Bytes: number;
}

export interface CandidateValidationResult {
  candidateFingerprint: string;
  snapshotProjectVersion: string;
  checks: CandidateValidationCheck[];
  issues: CandidateValidationIssue[];
  usage: CandidateValidationUsage;
  valid?: boolean;
}

export interface CandidateArtifactResponse {
  schemaVersion: 'YANBAN_CANDIDATE_ARTIFACT_V1';
  artifactId: number;
  projectId: number;
  projectVersion: string;
  fingerprint: string;
  governanceStatus: CandidateGovernanceStatus;
  applicationStatus: 'NOT_APPLIED';
  changes: CandidateFileChange[];
  reviewDiff: CandidateReviewDiff;
  validation: CandidateValidationResult;
}

export function candidateReviewFailure(status?: number): CandidateReviewState {
  if (status === 409) return 'STALE';
  if (status === 422) return 'INVALID';
  return 'ERROR';
}

export function isCandidateArtifactV1(value: unknown): value is CandidateArtifactResponse {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<CandidateArtifactResponse>;
  return candidate.schemaVersion === 'YANBAN_CANDIDATE_ARTIFACT_V1'
    && candidate.applicationStatus === 'NOT_APPLIED'
    && typeof candidate.artifactId === 'number'
    && typeof candidate.projectId === 'number'
    && typeof candidate.projectVersion === 'string'
    && typeof candidate.fingerprint === 'string'
    && Array.isArray(candidate.changes)
    && candidate.changes.length > 0
    && !!candidate.reviewDiff
    && !!candidate.validation;
}

export function listArtifacts(sessionId?: number) {
  return http.get<ArtifactResponse[]>('/artifacts', { params: { sessionId, limit: 100 } });
}

export function getCandidateChange(artifactId: number) {
  return http.get<CandidateArtifactResponse>(`/artifacts/${artifactId}/candidate`);
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
