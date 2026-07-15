import { beforeEach, describe, expect, it, vi } from 'vitest';

const http = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock('../src/api/http', () => ({ default: http }));

import {
  candidateReviewFailure,
  getCandidateChange,
  isCandidateArtifactV1,
  listArtifacts,
  type CandidateArtifactResponse,
} from '../src/api/artifact';

const candidate: CandidateArtifactResponse = {
  schemaVersion: 'YANBAN_CANDIDATE_ARTIFACT_V1',
  artifactId: 41,
  projectId: 7,
  projectVersion: 'a'.repeat(64),
  fingerprint: 'b'.repeat(64),
  governanceStatus: 'VALIDATED',
  applicationStatus: 'NOT_APPLIED',
  changes: [{
    type: 'MODIFY',
    projectVersion: 'a'.repeat(64),
    relativePath: 'paper/main.tex',
    baseFileHash: 'c'.repeat(64),
    resultFileHash: 'd'.repeat(64),
    candidateText: { text: 'replacement', utf8Bytes: 11, contentHash: 'd'.repeat(64) },
    evidenceRefs: [{
      projectVersion: 'a'.repeat(64),
      relativePath: 'paper/main.tex',
      fileHash: 'c'.repeat(64),
      range: { startLine: 2, endLine: 4 },
      parserVersion: 'plain-text-v1',
      trustLabel: 'VERIFIED',
    }],
  }],
  reviewDiff: {
    format: 'FULL_TEXT_REPLACEMENT_V1',
    sourceCandidateFingerprint: 'b'.repeat(64),
    projectVersion: 'a'.repeat(64),
    entries: [{
      type: 'MODIFY',
      relativePath: 'paper/main.tex',
      baseFileHash: 'c'.repeat(64),
      resultFileHash: 'd'.repeat(64),
      replacementText: 'replacement',
    }],
  },
  validation: {
    candidateFingerprint: 'b'.repeat(64),
    snapshotProjectVersion: 'a'.repeat(64),
    checks: [
      { area: 'STRUCTURE', status: 'PASSED' },
      { area: 'VERSION', status: 'PASSED' },
      { area: 'EVIDENCE', status: 'PASSED' },
      { area: 'CONTENT_HASH', status: 'PASSED' },
      { area: 'BUDGET', status: 'PASSED' },
    ],
    issues: [],
    usage: {
      requestedChanges: 1,
      inspectedChanges: 1,
      requestedEvidenceRefs: 1,
      inspectedEvidenceRefs: 1,
      requestedCandidateUtf8Bytes: 11,
      inspectedCandidateUtf8Bytes: 11,
    },
  },
};

describe('Candidate artifact API client', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists session artifacts and revalidates one Candidate through the read-only endpoint', () => {
    listArtifacts(17);
    getCandidateChange(41);

    expect(http.get).toHaveBeenNthCalledWith(1, '/artifacts', { params: { sessionId: 17, limit: 100 } });
    expect(http.get).toHaveBeenNthCalledWith(2, '/artifacts/41/candidate');
  });

  it('maps revalidation conflicts and invalid legacy artifacts without presenting success', () => {
    expect(candidateReviewFailure(409)).toBe('STALE');
    expect(candidateReviewFailure(422)).toBe('INVALID');
    expect(candidateReviewFailure(500)).toBe('ERROR');
    expect(candidateReviewFailure()).toBe('ERROR');
  });

  it('accepts only the versioned NOT_APPLIED Candidate envelope', () => {
    expect(isCandidateArtifactV1(candidate)).toBe(true);
    expect(isCandidateArtifactV1({ ...candidate, schemaVersion: 'LEGACY_CANDIDATE' })).toBe(false);
    expect(isCandidateArtifactV1({ ...candidate, applicationStatus: 'APPLIED' })).toBe(false);
    expect(isCandidateArtifactV1({ ...candidate, changes: [] })).toBe(false);
    expect(isCandidateArtifactV1({ relativePath: 'paper/main.tex', status: 'CANDIDATE' })).toBe(false);
  });
});
