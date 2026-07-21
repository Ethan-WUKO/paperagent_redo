import type { CandidateValidationResponse } from '@/api/project';

export interface CandidateValidationBinding {
  projectVersion: string;
  candidateFingerprint: string;
  acceptedChangeIndexes: number[];
}

export function candidateValidationCanApply(
  validation: CandidateValidationResponse,
  binding: CandidateValidationBinding,
) {
  return validation.status === 'SUCCEEDED'
    && validation.exitCode === 0
    && !validation.timedOut
    && validation.provider === 'docker-sbx'
    && validation.decisionStatus === 'PENDING'
    && validation.projectVersion === binding.projectVersion
    && validation.candidateFingerprint === binding.candidateFingerprint
    && validation.acceptedChangeIndexes.length === binding.acceptedChangeIndexes.length
    && validation.acceptedChangeIndexes.every(
      (value, index) => value === binding.acceptedChangeIndexes[index],
    );
}
