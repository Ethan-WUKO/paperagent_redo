import http from './http';

export interface TaskStatusResponse {
  taskType: 'paper_polish' | 'literature_search' | string;
  taskId: number;
  status: string;
  currentStage: string | null;
  createdAt: string;
  updatedAt: string;
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

export function cancelTask(taskId: number, request?: TaskCancelRequest) {
  return http.post<TaskCancelResponse>(`/tasks/${taskId}/cancel`, request ?? {});
}

export function getTaskStatus(taskId: number, taskType?: string) {
  return http.get<TaskStatusResponse>(`/tasks/${taskId}/status`, {
    params: taskType ? { taskType } : undefined,
  });
}

