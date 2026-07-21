import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const pagePath = fileURLToPath(new URL('../src/views/ProjectPreviewPage.vue', import.meta.url));
const source = readFileSync(pagePath, 'utf8');

describe('Project unified input contract', () => {
  it('has one user composer and only submits through the Project message route', () => {
    expect(source.match(/v-model:value="chatInput"/g)).toHaveLength(1);
    expect(source).toContain('@click="sendChat"');
    expect(source).toContain('sendProjectWithFallback(projectId, sessionId, content, requestId)');
    expect(source).not.toContain('v-model:value="planInput"');
    expect(source).not.toContain('@click="createPlan"');
    expect(source).not.toContain('createProjectPlan(');
  });

  it('presents Plans as execution details rather than a second submission mode', () => {
    expect(source).toContain('Execution details');
    expect(source).not.toContain('Create a governed Project plan');
    expect(source).not.toContain('Create plan</NButton>');
  });
});
