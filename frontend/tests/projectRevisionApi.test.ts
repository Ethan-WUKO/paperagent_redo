import { beforeEach, describe, expect, it, vi } from 'vitest';

const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../src/api/http', () => ({ default: http }));

import {
  applyProjectCandidate,
  cancelCandidateValidation,
  createCandidateValidation,
  exportProjectRevision,
  listProjectRevisions,
  listCandidateValidations,
  rejectCandidateValidation,
  rollbackProjectRevision,
} from '../src/api/project';

describe('Project revision API client', () => {
  beforeEach(() => vi.clearAllMocks());

  it('sends only selected change indexes while authority stays in route and headers', () => {
    const version = 'a'.repeat(64);
    applyProjectCandidate(7, 41, version, [0, 2], 'validation-1', 'apply-key-1');

    expect(http.post).toHaveBeenCalledWith('/projects/7/candidates/41/applications',
      { acceptedChangeIndexes: [0, 2], validationId: 'validation-1' },
      { headers: { 'Idempotency-Key': 'apply-key-1', 'If-Match': version } });
  });

  it('uses confirmed server-owned Candidate validation profiles and durable review routes', () => {
    const version = 'c'.repeat(64);
    createCandidateValidation(7, 41, version, 'MAVEN_TEST', [0, 2], 'verify-key-1');
    listCandidateValidations(7, 41);
    cancelCandidateValidation(7, 'validation-1');
    rejectCandidateValidation(7, 'validation-1');

    expect(http.post).toHaveBeenNthCalledWith(1, '/projects/7/candidates/41/validations',
      { profile: 'MAVEN_TEST', acceptedChangeIndexes: [0, 2], confirmed: true },
      { headers: { 'Idempotency-Key': 'verify-key-1', 'If-Match': version } });
    expect(http.get).toHaveBeenCalledWith('/projects/7/candidates/41/validations');
    expect(http.post).toHaveBeenNthCalledWith(2, '/projects/7/candidate-validations/validation-1/cancel', {});
    expect(http.post).toHaveBeenNthCalledWith(3, '/projects/7/candidate-validations/validation-1/reject', {});
  });

  it('accepts the server-owned Java source compile-and-run profile', () => {
    const version = 'e'.repeat(64);
    createCandidateValidation(7, 41, version, 'JAVA_SOURCE_RUN', [0], 'java-verify-key');

    expect(http.post).toHaveBeenCalledWith('/projects/7/candidates/41/validations',
      { profile: 'JAVA_SOURCE_RUN', acceptedChangeIndexes: [0], confirmed: true },
      { headers: { 'Idempotency-Key': 'java-verify-key', 'If-Match': version } });
  });

  it('uses real history, rollback, and bounded server export endpoints', () => {
    const version = 'b'.repeat(64);
    listProjectRevisions(7);
    rollbackProjectRevision(7, 12, version, 'rollback-key-1');
    exportProjectRevision(7, 12);

    expect(http.get).toHaveBeenNthCalledWith(1, '/projects/7/revisions');
    expect(http.post).toHaveBeenCalledWith('/projects/7/revisions/12/rollback', {},
      { headers: { 'Idempotency-Key': 'rollback-key-1', 'If-Match': version } });
    expect(http.get).toHaveBeenNthCalledWith(2, '/projects/7/revisions/12/export', { responseType: 'blob' });
  });
});
