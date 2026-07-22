import type { AgentPlanResponse, SendMessageResponse } from '@/api/agent';
import { requiresSandboxConfirmation } from '@/utils/projectSandboxConfirmation';

const CONTROLLED_PARTIAL_STOP_REASONS = new Set([
  'TOOL_CALL_BUDGET_EXHAUSTED',
  'MAX_STEPS_BUDGET_EXHAUSTED',
  'MODEL_OUTPUT_TRUNCATED',
  'PLAN_PARTIAL',
  'WAITING_FOR_USER',
]);
const INTERNAL_EVIDENCE_REF_LINE = /^[ \t]*\[projectEvidenceRefs=[^\r\n\]]*\][ \t]*(?:\r?\n|$)/gm;
const INTERNAL_EVIDENCE_REF = /\[projectEvidenceRefs=[^\r\n\]]*\]/g;

export function isControlledProjectPartial(response: SendMessageResponse) {
  return response.completionStatus === 'PARTIAL'
    && response.outcome === 'PARTIAL'
    && response.stopReason != null
    && CONTROLLED_PARTIAL_STOP_REASONS.has(response.stopReason)
    && Boolean(response.assistantContent?.trim());
}

export function withoutInternalProjectEvidenceRefs(content?: string | null) {
  if (!content) return '';
  return content
    .replace(INTERNAL_EVIDENCE_REF_LINE, '')
    .replace(INTERNAL_EVIDENCE_REF, '')
    .trimEnd();
}

export function withoutInternalRuntimeCodes(content?: string | null) {
  if (isSandboxConfirmationRequiredText(content)) return '';
  return withoutInternalProjectEvidenceRefs(content)
    .replace(/\bSANDBOX_CONFIRMATION_REQUIRED\b\s*:?\s*/gi, '')
    .replace(/\bDOMAIN_[A-Z0-9_]+\b\s*:?\s*/g, '')
    .replace(/\bDEPENDENCY_PARTIAL\b\s*:?\s*/g, '')
    .trim();
}

export function isSandboxConfirmationRequiredText(content?: string | null) {
  return Boolean(content && /\bSANDBOX_CONFIRMATION_REQUIRED\b/i.test(content));
}

export function projectPlanFailureReason(plan: AgentPlanResponse) {
  if (requiresSandboxConfirmation(plan)) return '';
  const planError = withoutInternalRuntimeCodes(plan.errorMessage);
  if (planError) return planError;
  const failedStep = [...plan.steps]
    .sort((left, right) => right.sortOrder - left.sortOrder)
    .find((step) => withoutInternalRuntimeCodes(step.errorMessage));
  return withoutInternalRuntimeCodes(failedStep?.errorMessage);
}

export function projectPlanLifecycle(plan: AgentPlanResponse) {
  return plan.status;
}

export function projectPlanExecutionOutcome(plan: AgentPlanResponse) {
  return plan.executionOutcome;
}

export function projectPlanDisplayStatus(plan: AgentPlanResponse, english: boolean) {
  const outcome = projectPlanExecutionOutcome(plan);
  if (outcome === 'TIMED_OUT') return english ? 'Timed out' : '已超时';
  return outcome;
}

export function projectPlanFinalAnswer(plan: AgentPlanResponse) {
  return withoutInternalProjectEvidenceRefs(plan.finalAnswer).trim();
}
