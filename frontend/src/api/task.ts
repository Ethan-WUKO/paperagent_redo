import http from './http';

export interface TaskStatusResponse {
  taskType: 'paper_polish' | 'literature_search' | string;
  taskId: number;
  status: string;
  currentStage: string | null;
  createdAt: string;
  updatedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  progressPercent: number | null;
  errorCode: string | null;
  errorMessage: string | null;
  cancellationReason: string | null;
  partialResultAvailable: boolean;
  completedArtifactCount: number;
  partialArtifactCount: number;
  lastEventId: number | null;
  lastEventType: string | null;
  lastEventMessage: string | null;
  lastEventAt: string | null;
  terminal: boolean;
  cancellable: boolean;
}

export interface TaskCancelResponse {
  taskType: 'paper_polish' | 'literature_search' | string;
  taskId: number;
  cancelAccepted: boolean;
  idempotent: boolean;
  beforeStatus: string;
  afterStatus: string;
  currentStage: string | null;
  message: string;
}

export interface TaskCancelRequest {
  taskType?: string;
  cancelReason?: string;
}

export interface TaskEventResponse {
  id: number;
  taskType: string;
  taskId: number;
  userId: number;
  eventType: string;
  stage: string | null;
  status: string;
  message: string | null;
  payloadJson: string | null;
  createdAt: string;
}

export function cancelTask(taskId: number, request?: TaskCancelRequest) {
  return http.post<TaskCancelResponse>(`/tasks/${taskId}/cancel`, request ?? {});
}

export function getTaskStatus(taskId: number, taskType?: string) {
  return http.get<TaskStatusResponse>(`/tasks/${taskId}/status`, {
    params: taskType ? { taskType } : undefined,
  });
}

export function listTaskEvents(taskId: number, taskType: string, afterEventId?: number, limit?: number) {
  return http.get<TaskEventResponse[]>(`/agent/tasks/${taskType}/${taskId}/events`, {
    params: {
      ...(afterEventId != null ? { afterEventId } : {}),
      ...(limit != null ? { limit } : {}),
    },
  });
}
