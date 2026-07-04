import http from './http';

export interface KbDocumentItem {
  id: number;
  userId: number;
  filename: string;
  status: 'UPLOADING' | 'PROCESSING' | 'READY' | 'FAILED';
  isPublic: boolean;
  sourceType: 'USER_UPLOAD' | 'DEMO_SEED' | string;
  mimeType: string | null;
  fileSize: number | null;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface KbDocumentResponse {
  id: number;
  userId: number;
  filename: string;
  status: string;
  isPublic: boolean;
  sourceType: string;
  createdAt: string;
  updatedAt: string;
}

export interface KbDocumentPreviewResponse {
  id: number;
  filename: string;
  status: string;
  mimeType: string | null;
  fileSize: number | null;
  totalChunks: number;
  previewChunks: number;
  maxChars: number;
  truncated: boolean;
  content: string;
}

export interface KnowledgeSearchResult {
  documentId: number;
  filename: string;
  chunkIndex: number;
  chunkText: string;
  score: number;
  isPublic: boolean;
}

export function listKbDocuments() {
  return http.get<KbDocumentItem[]>('/kb/documents');
}

export function deleteKbDocument(documentId: number) {
  return http.delete(`/kb/documents/${documentId}`);
}

export function previewKbDocument(documentId: number, maxChars = 20000) {
  return http.get<KbDocumentPreviewResponse>(`/kb/documents/${documentId}/preview`, {
    params: { maxChars },
  });
}

export function uploadChunk(payload: {
  uploadId: string;
  filename: string;
  chunkNumber: number;
  totalChunks: number;
  file: Blob;
  chunkMd5?: string;
}) {
  const formData = new FormData();
  formData.append('uploadId', payload.uploadId);
  formData.append('filename', payload.filename);
  formData.append('chunkNumber', String(payload.chunkNumber));
  formData.append('totalChunks', String(payload.totalChunks));
  if (payload.chunkMd5) {
    formData.append('chunkMd5', payload.chunkMd5);
  }
  formData.append('file', payload.file, payload.filename);
  return http.post('/upload/chunk', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
}

export function mergeKbUpload(payload: {
  uploadId: string;
  filename: string;
  totalChunks: number;
  mimeType: string;
  isPublic: boolean;
}) {
  return http.post<KbDocumentResponse>('/upload/merge', payload);
}

export function searchKnowledge(payload: { query: string; topK: number }) {
  return http.post<KnowledgeSearchResult[]>('/search', payload);
}
