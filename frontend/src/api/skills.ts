import http from './http';

export interface SkillListItemResponse {
  id: string;
  name: string;
  source: string;
  path: string;
  enabled: boolean;
  description: string;
}

export function listSkills() {
  return http.get<SkillListItemResponse[]>('/skills');
}
