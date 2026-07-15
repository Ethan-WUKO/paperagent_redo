import type { LongTermMemoryResponse } from '@/api/memory';

export interface MemoryActions {
  confirm: boolean;
  reject: boolean;
  correct: boolean;
  expiry: boolean;
  delete: boolean;
}

const NO_ACTIONS: MemoryActions = Object.freeze({
  confirm: false,
  reject: false,
  correct: false,
  expiry: false,
  delete: false,
});

export function isMemoryExpired(memory: Pick<LongTermMemoryResponse, 'expiresAt'>, now = Date.now()) {
  return Boolean(memory.expiresAt && new Date(memory.expiresAt).getTime() <= now);
}

export function memoryActions(
  memory: Pick<LongTermMemoryResponse, 'status' | 'confirmationStatus' | 'invalidatedAt' | 'expiresAt' | 'supersededByMemoryId'>,
  now = Date.now(),
): MemoryActions {
  if (memory.status !== 'ACTIVE' || memory.invalidatedAt || memory.supersededByMemoryId != null) {
    return NO_ACTIONS;
  }

  const expired = isMemoryExpired(memory, now);
  if (memory.confirmationStatus === 'UNCONFIRMED') {
    return {
      confirm: !expired,
      reject: !expired,
      correct: !expired,
      expiry: false,
      delete: false,
    };
  }
  if (memory.confirmationStatus === 'CONFIRMED' || memory.confirmationStatus === 'REJECTED') {
    return {
      confirm: false,
      reject: false,
      correct: !expired,
      expiry: true,
      delete: true,
    };
  }
  return NO_ACTIONS;
}

export function memoryApiError(error: unknown, fallback: string) {
  const { detail } = memoryApiErrorParts(error);
  return detail || (error as { message?: string })?.message || fallback;
}

export function isStaleMemoryApiError(error: unknown) {
  const { status, detail } = memoryApiErrorParts(error);
  return status === 409 && Boolean(detail && /stale|project.*version/i.test(detail));
}

function memoryApiErrorParts(error: unknown) {
  const response = (error as {
    response?: { status?: number; data?: string | { detail?: string; message?: string; error?: string } };
  })?.response;
  const data = response?.data;
  return {
    status: response?.status,
    detail: typeof data === 'string' ? data : data?.detail || data?.message || data?.error,
  };
}
