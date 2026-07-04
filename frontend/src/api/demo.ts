import http from './http';

export interface DemoConfigResponse {
  enabled: boolean;
  canonicalUrl: string;
  exampleQuestions: string[];
  notice: string;
  limitations: string[];
}

export function getDemoConfig() {
  return http.get<DemoConfigResponse>('/demo/config');
}
