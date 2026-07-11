<template>
  <AppLayout>
    <div class="paper-page workbench-page scholar-page scholar-page--paper">
      <WorkspaceHero
        kicker="Academic Writing"
        title="Paper Polish Workspace"
        subtitle="Revise manuscripts with retrieval-backed critique, citation support, live task events, and export-ready artifacts."
        storage-key="yanban.hero.paper"
      >
        <template #actions>
          <NSpace align="center">
            <NButton secondary @click="startNewPaperTask">New polish</NButton>
            <NButton secondary :loading="historyLoading" @click="loadHistory">Save draft</NButton>
            <NButton type="primary" :loading="submitting" @click="handleSubmit">Run Workflow</NButton>
          </NSpace>
        </template>
      </WorkspaceHero>

      <div class="paper-hero-panel">
        <NTag :type="currentTask ? statusTagType(currentTask.status) : 'default'" round>
          {{ currentTask?.status || '未创建任务' }}
        </NTag>
        <div class="paper-hero-metrics">
          <div>
            <span>进度</span>
            <strong>{{ progressPercent }}%</strong>
          </div>
          <div>
            <span>阶段</span>
            <strong>{{ currentStageLabel }}</strong>
          </div>
          <div>
            <span>产物</span>
            <strong>{{ canDownload ? 'Ready' : 'Pending' }}</strong>
          </div>
        </div>
      </div>

      <section class="paper-task-board">
        <div class="paper-task-board__head">
          <div>
            <div class="section-title">运行中的润色任务</div>
            <p>多个论文润色任务可以同时在后台运行；点选任务后，下方展示该任务的详细进度与确认项。</p>
          </div>
          <NButton size="small" secondary :loading="historyLoading" @click="refreshTaskBoard">刷新任务</NButton>
        </div>

        <div v-if="activeTaskCards.length > 0" class="paper-running-tasks">
          <article
            v-for="task in activeTaskCards"
            :key="task.id"
            class="paper-running-task"
            :class="{
              'paper-running-task--active': task.id === currentTaskId,
              'paper-running-task--waiting': task.status === 'WAITING_INPUT',
            }"
          >
            <button type="button" @click="openHistoryTask(task.id)">
              <span>{{ taskCardTitle(task) }}</span>
              <strong>{{ task.status === 'WAITING_INPUT' ? '待结构确认' : taskStatusLabel(task.status) }}</strong>
            </button>
            <div class="paper-running-task__meta">
              <span>{{ taskStageLabel(task) }}</span>
              <small>{{ formatDateTime(task.updatedAt) }}</small>
            </div>
            <div class="paper-running-task__flow" aria-label="论文润色任务进度">
              <span
                v-for="step in workflowSteps"
                :key="step.stage"
                :class="taskFlowStepClass(task, step.stage)"
                :title="step.label"
              >
                {{ step.shortLabel }}
              </span>
            </div>
          </article>
        </div>
        <NEmpty v-else description="暂无正在运行或等待确认的论文润色任务。" />
      </section>

      <div class="paper-polish-shell">
        <main class="paper-polish-main">
          <NGrid :cols="24" :x-gap="14" :y-gap="14" responsive="screen" item-responsive>
            <NGridItem span="24 l:11">
              <NCard class="workbench-card scholar-card paper-polish-card paper-input-card" :bordered="false">
                <template #header>
                  <div class="section-title">Input Manuscript</div>
                </template>
                <input ref="texInputRef" type="file" accept=".tex" class="kb-file-input" @change="handleTexFileChange" />
                <input ref="bibInputRef" type="file" accept=".bib" class="kb-file-input" @change="handleBibFileChange" />

                <div class="paper-polish-dropzone" @click="texInputRef?.click()">
                  <div class="paper-file-chip">TEX</div>
                  <div>
                    <strong>{{ selectedTexFile?.name || currentTask?.sourceFilename || 'Choose main.tex' }}</strong>
                    <span>
                      {{ selectedTexFile ? formatFileSize(selectedTexFile.size) : (currentTask?.sourceFilename ? `Task #${currentTask.id}` : 'Drag and drop your LaTeX entry file, or click to browse') }}
                    </span>
                  </div>
                  <NTag :type="selectedTexFile || currentTask ? 'success' : 'default'" round>
                    {{ selectedTexFile || currentTask ? 'Ready' : 'Required' }}
                  </NTag>
                </div>

                <div class="paper-input-actions">
                  <NButton v-if="currentTask" secondary @click="startNewPaperTask">Start new task</NButton>
                  <NButton secondary @click="texInputRef?.click()">Upload New</NButton>
                  <NButton secondary @click="bibInputRef?.click()">
                    {{ selectedBibFile ? selectedBibFile.name : 'Attach .bib file' }}
                  </NButton>
                </div>

                <div class="paper-config-grid">
                  <NForm :model="form" label-placement="top">
                    <NGrid :cols="24" :x-gap="12" responsive="screen" item-responsive>
                      <NFormItemGi span="24 m:8" label="Target language">
                        <NSelect v-model:value="form.targetLanguage" :options="languageOptions" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" label="Score threshold">
                        <NInputNumber v-model:value="form.scoreThreshold" :min="0" :max="100" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" label="Max section rounds">
                        <NInputNumber v-model:value="form.maxRounds" :min="1" :max="20" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" label="Repairs per round">
                        <NInputNumber v-model:value="form.innerMaxAttempts" :min="1" :max="20" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" label="Min literature">
                        <NInputNumber v-model:value="form.literatureMinCount" :min="1" :max="form.literatureCount" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" label="Max literature">
                        <NInputNumber v-model:value="form.literatureCount" :min="form.literatureMinCount" :max="100" style="width: 100%" />
                      </NFormItemGi>
                    </NGrid>
                  </NForm>
                </div>
              </NCard>
            </NGridItem>

            <NGridItem span="24 l:13">
              <NCard class="workbench-card scholar-card paper-polish-card paper-status-card-v2" :bordered="false">
                <template #header>
                  <div class="section-title">Task Status</div>
                </template>
                <template #header-extra>
                  <NTag :type="currentTask ? statusTagType(currentTask.status) : 'default'" round>
                    {{ currentTask?.status || 'No task' }}
                  </NTag>
                </template>

                <div class="paper-status-v2">
                  <div class="paper-status-v2__meta">
                    <div><span>Project</span><strong>{{ currentTask?.title || currentTask?.sourceFilename || 'New manuscript polish' }}</strong></div>
                    <div><span>Mode</span><strong>Full paper polish</strong></div>
                    <div><span>Owner</span><strong>Current researcher</strong></div>
                  </div>
                  <div class="paper-status-v2__progress">
                    <div class="paper-status-progress-head">
                      <span>Overall progress</span>
                      <strong>{{ progressPercent }}%</strong>
                    </div>
                    <NProgress type="line" :percentage="progressPercent" :show-indicator="false" status="success" />
                    <div class="paper-status-v2__facts">
                      <div><span>Current stage</span><strong>{{ currentStageLabel }}</strong></div>
                      <div><span>Sections</span><strong>{{ sectionProgressText }}</strong></div>
                      <div><span>Attempts</span><strong>{{ attemptProgressText }}</strong></div>
                      <div><span>Started</span><strong>{{ currentTask ? formatDateTime(currentTask.createdAt) : '-' }}</strong></div>
                    </div>
                  </div>
                </div>

                <NAlert v-if="currentTask?.errorMessage" type="error" title="Task error">
                  {{ currentTask.errorMessage }}
                </NAlert>
                <NAlert v-if="currentTask?.status === 'WAITING_INPUT' || pendingClarifications.length > 0" type="warning" title="结构确认待处理">
                  当前任务需要你确认论文结构。请在下方 Review Workspace 的 Clarifications 中提交选择，任务会继续后台执行。
                </NAlert>
                <div v-if="needsStructureConfirmation" class="paper-structure-confirmation-callout">
                  <div>
                    <strong>需要确认论文结构</strong>
                    <span>{{ pendingClarifications.length }} 个确认项等待处理，提交后任务会继续执行。</span>
                  </div>
                  <NButton type="warning" secondary @click="focusStructureConfirmation">去确认</NButton>
                </div>
                <div class="paper-status-actions">
                  <NButton secondary :disabled="!currentTaskId" @click="refreshTask">Refresh</NButton>
                  <NButton secondary :disabled="!currentTaskId" @click="connectSse">Reconnect SSE</NButton>
                  <NButton tertiary :disabled="!canPause" @click="handlePause">Pause</NButton>
                  <NButton tertiary :disabled="!canResume" @click="handleResume">Resume</NButton>
                  <NButton tertiary type="error" :disabled="!canStop" @click="handleStop">Stop</NButton>
                </div>
              </NCard>
            </NGridItem>
          </NGrid>

          <NCard class="workbench-card scholar-card paper-polish-card paper-workflow-card-v2" :bordered="false">
            <template #header>
              <div class="section-title">Workflow</div>
            </template>
            <div class="paper-workflow-v2">
              <div v-for="step in workflowSteps" :key="step.stage" class="paper-workflow-step-v2" :class="workflowStepClass(step.stage)">
                <span>{{ step.shortLabel }}</span>
                <strong>{{ step.label }}</strong>
                <small>{{ workflowStepDetail(step.stage) }}</small>
              </div>
            </div>
            <div v-if="currentTask" class="paper-workflow-activity" role="status" aria-live="polite">
              <strong>{{ workflowActivityTitle }}</strong>
              <span>{{ workflowActivityDetail }}</span>
            </div>
          </NCard>

          <NCard
            class="workbench-card scholar-card paper-polish-card"
            :class="{ 'paper-polish-card--collapsed': revisionSuggestionsCollapsed }"
            :bordered="false"
          >
            <template #header>
              <div class="section-title">Revision Suggestions</div>
            </template>
            <template #header-extra>
              <NSpace size="small" align="center">
                <NTag type="info" round>{{ suggestions.length }} total</NTag>
                <NButton
                  class="paper-card-collapse-button"
                  quaternary
                  circle
                  size="small"
                  :title="revisionSuggestionsCollapsed ? 'Expand revision suggestions' : 'Collapse revision suggestions'"
                  :aria-label="revisionSuggestionsCollapsed ? 'Expand revision suggestions' : 'Collapse revision suggestions'"
                  :aria-expanded="!revisionSuggestionsCollapsed"
                  @click="revisionSuggestionsCollapsed = !revisionSuggestionsCollapsed"
                >
                  <span aria-hidden="true">{{ revisionSuggestionsCollapsed ? '▾' : '▴' }}</span>
                </NButton>
              </NSpace>
            </template>

            <NEmpty v-if="!revisionSuggestionsCollapsed && suggestions.length === 0" description="No revision suggestions yet. Run a workflow or open a completed task." />
            <div v-else-if="!revisionSuggestionsCollapsed" class="paper-suggestion-table-v2">
              <div class="paper-suggestion-table-v2__head">
                <span>Suggestion</span>
                <span>Evidence</span>
                <span>Severity</span>
                <span>State</span>
              </div>
              <article v-for="suggestion in visibleSuggestions" :key="suggestion.id" class="paper-suggestion-row-v2">
                <div>
                  <strong>{{ suggestion.category }}</strong>
                  <p>{{ suggestion.statement }}</p>
                  <small v-if="suggestionDecisionReason(suggestion)" class="paper-suggestion-decision">
                    {{ suggestionDecisionReason(suggestion) }}
                  </small>
                </div>
                <span>{{ suggestion.evidenceCount }} evidence cards</span>
                <NTag :type="suggestion.severity === 'HIGH' ? 'error' : suggestion.severity === 'LOW' ? 'success' : 'warning'" size="small">
                  {{ suggestion.severity || 'Info' }}
                </NTag>
                <NTag :type="suggestionStateType(suggestion)" size="small">
                  {{ suggestionStateLabel(suggestion) }}
                </NTag>
              </article>
            </div>
          </NCard>

          <NCard
            class="workbench-card scholar-card paper-polish-card"
            :class="{ 'paper-polish-card--collapsed': evidenceSnippetsCollapsed }"
            :bordered="false"
          >
            <template #header>
              <div class="section-title">Evidence Snippets</div>
            </template>
            <template #header-extra>
              <NButton
                class="paper-card-collapse-button"
                quaternary
                circle
                size="small"
                :title="evidenceSnippetsCollapsed ? 'Expand evidence snippets' : 'Collapse evidence snippets'"
                :aria-label="evidenceSnippetsCollapsed ? 'Expand evidence snippets' : 'Collapse evidence snippets'"
                :aria-expanded="!evidenceSnippetsCollapsed"
                @click="evidenceSnippetsCollapsed = !evidenceSnippetsCollapsed"
              >
                <span aria-hidden="true">{{ evidenceSnippetsCollapsed ? '▾' : '▴' }}</span>
              </NButton>
            </template>
            <div v-if="!evidenceSnippetsCollapsed && literatureSupportCards.length > 0" class="paper-evidence-grid-v2">
              <article v-for="card in literatureSupportCards" :key="card.id" class="paper-evidence-card-v2">
                <strong>{{ card.title }}</strong>
                <span>{{ formatAuthors(card.authors) }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</span>
                <p>{{ card.citationCount == null ? 'Citation metadata unavailable.' : `${card.citationCount} citations found in metadata.` }}</p>
                <div>
                  <NTag size="small" type="success">Evidence</NTag>
                  <a v-if="card.url || card.doi" :href="card.url || doiUrl(card.doi)" target="_blank" rel="noreferrer">View source</a>
                </div>
              </article>
            </div>
            <NEmpty v-else-if="!evidenceSnippetsCollapsed" description="No evidence snippets yet." />
          </NCard>
        </main>

        <aside class="paper-polish-side">
          <NCard class="workbench-card scholar-card paper-polish-card" :bordered="false">
            <template #header>
              <div class="section-title">Literature Support</div>
            </template>
            <template #header-extra>
              <NTag type="info" round>{{ bibliographyCards.length }}</NTag>
            </template>
            <div class="paper-support-list-v2">
              <article v-for="card in literatureSupportCards" :key="`support-${card.id}`" class="paper-support-card-v2">
                <div>
                  <strong>{{ card.title }}</strong>
                  <span>{{ formatAuthors(card.authors) }} · {{ card.publicationYear || '-' }}</span>
                  <p>{{ card.venue || 'Venue unavailable' }}</p>
                </div>
                <NTag type="success" size="small">Support</NTag>
              </article>
              <NEmpty v-if="literatureSupportCards.length === 0" description="No literature support yet." />
            </div>
          </NCard>

          <NCard class="workbench-card scholar-card paper-polish-card" :bordered="false">
            <template #header>
              <div class="section-title">Export Artifacts</div>
            </template>
            <template #header-extra>
              <NButton size="small" secondary :disabled="!canDownload" :loading="downloading" @click="handleDownload">Download</NButton>
            </template>
            <div class="paper-artifact-list-v2">
              <article v-for="artifact in exportArtifacts" :key="artifact.id" class="paper-artifact-row-v2">
                <span>{{ artifactDisplayName(artifact).slice(0, 2).toUpperCase() }}</span>
                <div>
                  <strong>{{ artifactDisplayName(artifact) }}</strong>
                  <small>v{{ artifact.version }} · {{ formatDateTime(artifact.createdAt) }}</small>
                </div>
              </article>
              <NEmpty v-if="exportArtifacts.length === 0" description="No export artifacts yet." />
            </div>
          </NCard>

          <NCard class="workbench-card scholar-card paper-polish-card" :bordered="false">
            <template #header>
              <div class="section-title">Live Task Events</div>
            </template>
            <template #header-extra>
              <NTag :type="sseStatus === 'connected' ? 'success' : 'default'" round>SSE {{ sseStatusText }}</NTag>
            </template>
            <div class="paper-live-events-v2">
              <article v-for="(event, index) in liveTaskEvents" :key="`${event.timestamp}-${index}`">
                <i />
                <span>{{ formatDateTime(event.timestamp) }}</span>
                <strong>{{ event.message }}</strong>
              </article>
              <NEmpty v-if="liveTaskEvents.length === 0" description="No live events yet." />
            </div>
          </NCard>

          <NCard class="workbench-card scholar-card paper-polish-card" :bordered="false">
            <template #header>
              <div class="section-title">History</div>
            </template>
            <template #header-extra>
              <NButton size="small" secondary :loading="historyLoading" @click="loadHistory">Refresh</NButton>
            </template>
            <div class="paper-history-compact-v2">
              <article v-for="task in historyTasks" :key="task.id" :class="{ 'paper-history-compact-v2--active': task.id === currentTaskId }">
                <button type="button" @click="openHistoryTask(task.id)">{{ task.title || task.sourceFilename || `Task ${task.id}` }}</button>
                <NTag size="small" :type="statusTagType(task.status)">{{ task.status }}</NTag>
              </article>
              <NEmpty v-if="historyTasks.length === 0" description="No history yet." />
            </div>
          </NCard>
        </aside>
      </div>

      <NCard id="paper-structure-confirmation" class="workbench-card scholar-card paper-polish-card paper-review-workspace-v2" :bordered="false">
        <template #header>
          <div class="section-title">Review Workspace</div>
        </template>
        <NTabs v-model:value="activeReviewTab" type="segment" animated>
          <NTabPane name="clarification" :tab="pendingClarifications.length ? `Clarifications (${pendingClarifications.length})` : 'Clarifications'">
            <div v-if="clarifications.length > 0" class="paper-clarification-panel">
              <NAlert v-if="pendingBlockingClarifications.length > 0" type="warning" title="Input required">
                Some structure questions require confirmation before the workflow continues.
              </NAlert>
              <div v-for="item in clarifications" :key="item.id" class="paper-clarification-item">
                <div class="paper-clarification-item__title">
                  <span>{{ clarificationQuestion(item).message || item.type }}</span>
                  <NTag size="small" :type="item.status === 'PENDING' ? 'warning' : 'success'">{{ item.status }}</NTag>
                </div>
                <NRadioGroup v-if="item.status === 'PENDING'" v-model:value="clarificationAnswers[item.id]" class="paper-option-group">
                  <NSpace vertical size="small">
                    <NRadio v-for="option in clarificationOptions(item).options" :key="option" :value="option">{{ option }}</NRadio>
                  </NSpace>
                </NRadioGroup>
                <div v-else class="paper-clarification-item__answer">{{ answeredOption(item) }}</div>
                <NSpace v-if="item.status === 'PENDING'" size="small">
                  <NButton size="small" type="primary" :loading="clarificationSubmitting" @click="submitClarification(item)">Submit</NButton>
                  <NButton v-if="!clarificationQuestion(item).blocking" size="small" tertiary :loading="clarificationSubmitting" @click="skipClarification(item)">Skip</NButton>
                </NSpace>
              </div>
              <NButton v-if="pendingClarifications.length > 0" secondary block :loading="clarificationSubmitting" @click="keepAllClarifications">
                Keep all defaults
              </NButton>
            </div>
            <NEmpty v-else description="No clarifications yet." />
          </NTabPane>

          <NTabPane name="sections" :tab="`Sections (${sections.length})`">
            <div v-if="sections.length > 0" class="paper-section-role-panel">
              <div v-for="section in sections" :key="section.id" class="paper-section-role-row">
                <div>
                  <strong>{{ section.orderIndex + 1 }}. {{ section.title }}</strong>
                  <small>{{ section.roleSource || 'auto' }} · confidence {{ formatConfidence(section.roleConfidence) }}</small>
                </div>
                <NSelect :value="section.role" :options="sectionRoleOptions" size="small" @update:value="(role) => handleSectionRoleChange(section.id, role)" />
              </div>
            </div>
            <NEmpty v-else description="No section roles yet." />
          </NTabPane>

          <NTabPane name="preview" :tab="`Preview (${previewSections.length + suggestions.length})`">
            <div class="paper-preview-panel">
              <NButtonGroup>
                <NButton :type="previewMode === 'basic' ? 'primary' : 'default'" size="small" @click="previewMode = 'basic'">Recommendations</NButton>
                <NButton :type="previewMode === 'advanced' ? 'primary' : 'default'" size="small" @click="previewMode = 'advanced'">Diff + cite patch</NButton>
              </NButtonGroup>
              <div v-for="section in previewSections" :key="`preview-${section.id}`" class="paper-diff-card">
                <div class="paper-diff-card__title">{{ section.orderIndex + 1 }}. {{ section.title }} · {{ section.polishStatus || 'NOT_POLISHED' }}</div>
                <pre v-if="section.diffJson" class="paper-code-preview">{{ readableDiff(section.diffJson) }}</pre>
                <pre v-else-if="section.reviewJson" class="paper-code-preview">{{ prettyJson(section.reviewJson) }}</pre>
              </div>
              <div v-for="suggestion in suggestions" :key="suggestion.id" class="paper-suggestion-card" :class="`paper-suggestion-card--${suggestion.honestyGrade}`">
                <strong>{{ suggestion.category }} · {{ suggestion.severity || '-' }}</strong>
                <p>{{ suggestion.statement }}</p>
                <small>{{ suggestion.honestyReason }} · evidence {{ suggestion.evidenceCount }} · {{ suggestion.track }} · {{ suggestion.status }}</small>
                <br v-if="suggestionDecisionReason(suggestion)" />
                <small v-if="suggestionDecisionReason(suggestion)" class="paper-suggestion-decision">{{ suggestionDecisionReason(suggestion) }}</small>
                <pre v-if="suggestion.patchJson && previewMode === 'advanced'" class="paper-code-preview">{{ prettyJson(suggestion.patchJson) }}</pre>
              </div>
              <NEmpty v-if="suggestions.length === 0 && previewSections.length === 0" description="No preview results yet." />
            </div>
          </NTabPane>

          <NTabPane name="report" :tab="`Report (${bibliographyCards.length})`">
            <div class="paper-report-panel">
              <div v-for="card in bibliographyCards" :key="`bib-${card.id}`" class="paper-bib-card">
                <strong>{{ card.title }}</strong>
                <small>{{ formatAuthors(card.authors) }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</small>
                <div class="paper-bib-card__links">
                  <a v-if="card.doi" :href="doiUrl(card.doi)" target="_blank" rel="noreferrer">DOI: {{ card.doi }}</a>
                  <a v-if="card.url" :href="card.url" target="_blank" rel="noreferrer">URL</a>
                  <a v-if="card.pdfUrl" :href="card.pdfUrl" target="_blank" rel="noreferrer">PDF</a>
                </div>
              </div>
              <NEmpty v-if="bibliographyCards.length === 0" description="No bibliography report yet." />
            </div>
          </NTabPane>
        </NTabs>
      </NCard>

      <div class="paper-steps-bar">
        <div class="paper-step" :class="{ 'paper-step--active': !currentTask }">
          <span>1</span>
          <div><strong>上传论文</strong><small>选择 tex / bib 与参数</small></div>
        </div>
        <div class="paper-step" :class="{ 'paper-step--active': currentTask && !canDownload }">
          <span>2</span>
          <div><strong>处理中</strong><small>查看 SSE 日志</small></div>
        </div>
        <div class="paper-step" :class="{ 'paper-step--active': canDownload }">
          <span>3</span>
          <div><strong>下载结果</strong><small>保存最终文件</small></div>
        </div>
      </div>

      <NGrid :cols="24" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGridItem span="24 l:7">
          <NSpace vertical size="medium">
          <NCard class="workbench-card" :bordered="false">
            <template #header><div class="section-title">上传与参数</div></template>
            <NSpace vertical size="large">
              <NForm :model="form" label-placement="top">
                <NFormItem label="LaTeX 主文件（.tex，必填）">
                  <input ref="texInputRef" type="file" accept=".tex" class="kb-file-input" @change="handleTexFileChange" />
                  <div class="upload-dropzone" @click="texInputRef?.click()">
                    <strong>{{ selectedTexFile ? selectedTexFile.name : '点击选择 main.tex' }}</strong>
                    <span>{{ selectedTexFile ? formatFileSize(selectedTexFile.size) : '主入口 tex 文件，后续会作为 LaTeX 解析入口' }}</span>
                  </div>
                </NFormItem>
                <NFormItem label="参考文献文件（.bib，可选）">
                  <input ref="bibInputRef" type="file" accept=".bib" class="kb-file-input" @change="handleBibFileChange" />
                  <div class="upload-dropzone" @click="bibInputRef?.click()">
                    <strong>{{ selectedBibFile ? selectedBibFile.name : '点击选择 refs.bib（可选）' }}</strong>
                    <span>{{ selectedBibFile ? formatFileSize(selectedBibFile.size) : '无 .bib 时也支持内联 thebibliography 样例' }}</span>
                  </div>
                </NFormItem>
                <NGrid :cols="2" :x-gap="12">
                  <NFormItemGi label="目标语言">
                    <NSelect v-model:value="form.targetLanguage" :options="languageOptions" />
                  </NFormItemGi>
                  <NFormItemGi label="评分阈值">
                    <NInputNumber v-model:value="form.scoreThreshold" :min="0" :max="100" style="width: 100%" />
                  </NFormItemGi>
                  <NFormItemGi label="最大轮次">
                    <NInputNumber v-model:value="form.maxRounds" :min="1" :max="20" style="width: 100%" />
                  </NFormItemGi>
                  <NFormItemGi label="单节尝试">
                    <NInputNumber v-model:value="form.innerMaxAttempts" :min="1" :max="20" style="width: 100%" />
                  </NFormItemGi>
                  <NFormItemGi label="推荐文献最少数量">
                    <NInputNumber v-model:value="form.literatureMinCount" :min="1" :max="form.literatureCount" style="width: 100%" />
                  </NFormItemGi>
                  <NFormItemGi label="推荐文献最多数量">
                    <NInputNumber v-model:value="form.literatureCount" :min="form.literatureMinCount" :max="100" style="width: 100%" />
                  </NFormItemGi>
                </NGrid>
              </NForm>
              <NButton type="primary" block :loading="submitting" @click="handleSubmit">开始处理</NButton>
            </NSpace>
          </NCard>

          <NCard class="workbench-card history-card" :bordered="false">
            <template #header><div class="section-title">历史任务</div></template>
            <template #header-extra><NButton size="small" tertiary :loading="historyLoading" @click="loadHistory">刷新</NButton></template>
            <div class="paper-history-list">
              <NEmpty v-if="historyTasks.length === 0" description="暂无历史任务" />
              <div v-for="task in historyTasks" :key="task.id" class="paper-history-item" :class="{ 'paper-history-item--active': task.id === currentTaskId }">
                <div class="paper-history-item__head">
                  <button type="button" class="paper-history-title" @click="openHistoryTask(task.id)">{{ task.title || task.sourceFilename || `Task ${task.id}` }}</button>
                  <NTag size="small" :type="statusTagType(task.status)">{{ task.status }}</NTag>
                </div>
                <div class="paper-history-item__meta">
                  创建：{{ formatDateTime(task.createdAt) }}<br />
                  更新：{{ formatDateTime(task.updatedAt) }}
                </div>
                <div class="paper-history-files">
                  <span v-for="artifact in downloadableHistoryArtifacts(task)" :key="artifact.id">{{ artifactDisplayName(artifact) }}</span>
                  <span v-if="downloadableHistoryArtifacts(task).length === 0">暂无结果文件</span>
                </div>
                <NSpace size="small">
                  <NButton size="tiny" secondary @click="openHistoryTask(task.id)">查看</NButton>
                  <NButton size="tiny" type="primary" :disabled="!historyTaskDownloadable(task)" :loading="downloadingTaskId === task.id" @click="downloadHistoryTask(task.id)">下载结果</NButton>
                </NSpace>
              </div>
            </div>
          </NCard>
          </NSpace>
        </NGridItem>

        <NGridItem span="24 l:10">
          <NCard class="workbench-card" :bordered="false">
            <template #header><div class="section-title">实时进度</div></template>
            <template #header-extra><NTag :type="sseStatus === 'connected' ? 'success' : 'default'">SSE {{ sseStatusText }}</NTag></template>
            <NSpace vertical size="large">
              <div class="paper-status-box status-grid">
                <div><span>任务 ID</span><strong>{{ currentTask?.id ?? '-' }}</strong></div>
                <div><span>当前状态</span><strong>{{ currentTask?.status ?? '-' }}</strong></div>
                <div><span>当前阶段</span><strong>{{ currentStageLabel }}</strong></div>
                <div><span>整体进度</span><strong>{{ progressPercent }}%</strong></div>
                <div v-if="currentTask?.errorMessage" class="status-grid__wide"><span>错误信息</span><strong>{{ currentTask.errorMessage }}</strong></div>
              </div>

              <div class="paper-stage-panel">
                <NProgress type="line" :percentage="progressPercent" :show-indicator="false" status="success" />
                <div class="paper-stage-chain">
                  <div v-for="step in workflowSteps" :key="step.stage" class="paper-stage-chip" :class="stageChipClass(step.stage)">
                    <span>{{ step.label }}</span>
                  </div>
                </div>
                <div class="paper-progress-detail">
                  <span>章节：{{ sectionProgressText }}</span>
                  <span>尝试：{{ attemptProgressText }}</span>
                </div>
              </div>

              <NSpace>
                <NButton secondary :disabled="!currentTaskId" @click="refreshTask">刷新</NButton>
                <NButton secondary :disabled="!currentTaskId" @click="connectSse">重连 SSE</NButton>
                <NButton tertiary :disabled="!canPause" @click="handlePause">暂停</NButton>
                <NButton tertiary :disabled="!canResume" @click="handleResume">继续</NButton>
                <NButton tertiary type="error" :disabled="!canStop" @click="handleStop">停止</NButton>
              </NSpace>
              <div class="paper-event-list timeline-list">
                <NEmpty v-if="events.length === 0" description="等待任务事件" />
                <div v-for="(event, index) in events" :key="`${event.timestamp}-${index}`" class="paper-event-item timeline-item">
                  <div class="paper-event-item__meta">{{ eventDisplayType(event.type) }} · {{ stageLabel(event.stage) }} · {{ formatDateTime(event.timestamp) }}</div>
                  <div>{{ event.message }}</div>
                  <div v-if="event.sectionTitle || event.currentSection || event.attempt" class="paper-event-item__hint">
                    {{ eventProgressHint(event) }}
                  </div>
                </div>
              </div>
              <NCollapse v-if="events.length > 0" class="paper-debug-collapse">
                <NCollapseItem title="原始 SSE 事件日志（调试）" name="raw-events">
                  <pre class="paper-raw-events">{{ rawEventLog }}</pre>
                </NCollapseItem>
              </NCollapse>
            </NSpace>
          </NCard>
        </NGridItem>

        <NGridItem span="24 l:7">
          <NCard class="workbench-card result-card result-hub-card" :bordered="false">
            <template #header>
              <div class="result-hub-title">
                <div>
                  <div class="section-title">结果中心</div>
                  <small>下载、确认、预览与审查集中管理</small>
                </div>
              </div>
            </template>
            <div class="result-hub">
              <div class="result-download-strip">
                <div class="result-download-strip__main">
                  <NTag :type="canDownload ? 'success' : 'default'" round>{{ canDownload ? '可下载' : '等待产物' }}</NTag>
                  <div>
                    <strong>{{ resultFileText }}</strong>
                    <span>原始文件：{{ currentTask?.sourceFilename || '-' }}</span>
                  </div>
                </div>
                <NButton type="primary" :loading="downloading" :disabled="!canDownload" @click="handleDownload">下载结果</NButton>
              </div>

              <NTabs v-model:value="activeResultTab" type="segment" animated class="paper-result-tabs" pane-class="paper-result-pane">
                <NTabPane name="overview" tab="总览">
                  <div class="paper-result-scroll">
                    <NSpace vertical size="medium">
                      <NAlert type="info" title="当前说明">
                        当前结果以 LaTeX 三件套为目标；真实编译与逐条 patch 组装仍按后续验收逐步增强。
                      </NAlert>
                      <div class="result-overview-grid">
                        <div class="result-overview-card">
                          <span>结构确认</span>
                          <strong>{{ pendingClarifications.length > 0 ? `${pendingClarifications.length} 个待确认` : (clarifications.length > 0 ? '已处理' : '暂无') }}</strong>
                        </div>
                        <div class="result-overview-card">
                          <span>章节角色</span>
                          <strong>{{ sections.length }} 节</strong>
                        </div>
                        <div class="result-overview-card">
                          <span>预览建议</span>
                          <strong>{{ suggestions.length }} 条</strong>
                        </div>
                        <div class="result-overview-card">
                          <span>已采纳</span>
                          <strong>{{ acceptedSuggestions.length }} 条</strong>
                        </div>
                      </div>
                      <div class="paper-artifact-summary result-artifact-card">
                        <div>suggested.bib：{{ suggestedBibArtifacts.length }} 个版本</div>
                        <div>polished.tex：{{ polishedTexArtifacts.length }} 个版本</div>
                        <div>retrieved-literature：{{ retrievedLiteratureArtifacts.length }} 个诊断文件</div>
                        <div>结果文件：{{ resultFileText }}</div>
                      </div>
                      <NAlert type="warning" title="免责声明">
                        审查报告和 suggested.bib 是 AI 辅助结果。请在投稿前核验每条引用、事实陈述、DOI/URL 与 LaTeX 改动。
                      </NAlert>
                    </NSpace>
                  </div>
                </NTabPane>

                <NTabPane name="clarification" :tab="pendingClarifications.length ? `结构(${pendingClarifications.length})` : '结构'">
                  <div class="paper-result-scroll">
                    <div v-if="clarifications.length > 0" class="paper-clarification-panel">
                      <div class="paper-panel-heading">
                        <strong>结构确认</strong>
                        <NTag :type="pendingClarifications.length > 0 ? 'warning' : 'success'" size="small">
                          {{ pendingClarifications.length > 0 ? `${pendingClarifications.length} 个待确认` : '已处理' }}
                        </NTag>
                      </div>
                      <NAlert v-if="pendingBlockingClarifications.length > 0" type="warning" title="需要你的确认">
                        阻塞型问题必须先回答。默认选项会优先保持原结构，避免任务跑偏。
                      </NAlert>
                      <div v-for="item in clarifications" :key="item.id" class="paper-clarification-item">
                        <div class="paper-clarification-item__title">
                          <span>{{ clarificationQuestion(item).message || item.type }}</span>
                          <NTag size="small" :type="clarificationQuestion(item).blocking ? 'error' : 'info'">
                            {{ clarificationQuestion(item).blocking ? '必须回答' : '可跳过' }}
                          </NTag>
                        </div>
                        <div class="paper-clarification-item__meta">
                          类型：{{ item.type }} · 相关章节序号：{{ clarificationQuestion(item).relatedSectionOrderIndex ?? '-' }}
                        </div>
                        <NRadioGroup v-if="item.status === 'PENDING'" v-model:value="clarificationAnswers[item.id]" class="paper-option-group">
                          <NSpace vertical size="small">
                            <NRadio v-for="option in clarificationOptions(item).options" :key="option" :value="option">
                              {{ option }}
                            </NRadio>
                          </NSpace>
                        </NRadioGroup>
                        <div v-else class="paper-clarification-item__answer">已回答：{{ answeredOption(item) }}</div>
                        <NSpace v-if="item.status === 'PENDING'" size="small">
                          <NButton size="small" type="primary" :loading="clarificationSubmitting" @click="submitClarification(item)">提交</NButton>
                          <NButton v-if="!clarificationQuestion(item).blocking" size="small" tertiary :loading="clarificationSubmitting" @click="skipClarification(item)">跳过</NButton>
                        </NSpace>
                      </div>
                      <NButton v-if="pendingClarifications.length > 0" secondary block :loading="clarificationSubmitting" @click="keepAllClarifications">
                        全部保持原样
                      </NButton>
                    </div>
                    <NEmpty v-else description="暂无结构确认项" />
                  </div>
                </NTabPane>

                <NTabPane name="sections" :tab="`章节(${sections.length})`">
                  <div class="paper-result-scroll">
                    <div v-if="sections.length > 0" class="paper-section-role-panel">
                      <div class="paper-panel-heading"><strong>章节角色</strong><span>可手动修正识别结果</span></div>
                      <div v-for="section in sections" :key="section.id" class="paper-section-role-row">
                        <div>
                          <strong>{{ section.orderIndex + 1 }}. {{ section.title }}</strong>
                          <small>{{ section.roleSource || 'auto' }} · confidence {{ formatConfidence(section.roleConfidence) }}</small>
                        </div>
                        <NSelect :value="section.role" :options="sectionRoleOptions" size="small" @update:value="(role) => handleSectionRoleChange(section.id, role)" />
                      </div>
                    </div>
                    <NEmpty v-else description="暂无章节角色结果" />
                  </div>
                </NTabPane>

                <NTabPane name="preview" :tab="`预览(${previewSections.length + suggestions.length})`">
                  <div class="paper-result-scroll">
                    <div class="paper-preview-panel">
                      <div class="paper-panel-heading">
                        <strong>在线预览与逐条采纳</strong>
                        <NTag size="small" type="info">已采纳 {{ acceptedSuggestions.length }}</NTag>
                      </div>
                      <NButtonGroup>
                        <NButton :type="previewMode === 'basic' ? 'primary' : 'default'" size="small" @click="previewMode = 'basic'">基础版：只看建议</NButton>
                        <NButton :type="previewMode === 'advanced' ? 'primary' : 'default'" size="small" @click="previewMode = 'advanced'">进阶版：Diff + cite patch</NButton>
                      </NButtonGroup>
                      <NAlert :type="previewMode === 'basic' ? 'info' : 'warning'" :title="previewMode === 'basic' ? '基础版预览' : '进阶版预览'">
                        {{ previewMode === 'basic' ? '只展示建议、review 和 suggested.bib，不直接改写原文。' : '只会采纳 A 类且 evidence 真实可追溯的补丁；B 类仅作为骨架和批评展示。' }}
                      </NAlert>
                      <NEmpty v-if="suggestions.length === 0 && previewSections.length === 0" description="暂无 diff 或建议结果" />
                      <div v-for="section in previewSections" :key="`preview-${section.id}`" class="paper-diff-card">
                        <div class="paper-diff-card__title">
                          <span>{{ section.orderIndex + 1 }}. {{ section.title }} · {{ section.polishStatus || 'NOT_POLISHED' }}</span>
                          <NSpace size="small" align="center">
                            <NTag size="small" :type="section.revisionStatus === 'ACCEPTED' ? 'success' : section.revisionStatus === 'REJECTED' ? 'error' : 'warning'">
                              {{ section.revisionStatus || 'PENDING' }}
                            </NTag>
                            <NButton size="tiny" tertiary :disabled="sectionRevisionSubmitting === section.id" @click="updateSectionRevisionStatus(section, 'ACCEPTED')">采纳</NButton>
                            <NButton size="tiny" tertiary :disabled="sectionRevisionSubmitting === section.id" @click="updateSectionRevisionStatus(section, 'REJECTED')">拒绝</NButton>
                            <NButton size="tiny" tertiary :disabled="sectionRevisionSubmitting === section.id" @click="updateSectionRevisionStatus(section, 'PENDING')">待处理</NButton>
                          </NSpace>
                        </div>
                        <pre v-if="section.diffJson" class="paper-code-preview">{{ readableDiff(section.diffJson) }}</pre>
                        <pre v-else-if="section.reviewJson" class="paper-code-preview">{{ prettyJson(section.reviewJson) }}</pre>
                      </div>
                      <div v-for="suggestion in suggestions" :key="suggestion.id" class="paper-suggestion-card" :class="`paper-suggestion-card--${suggestion.honestyGrade}`">
                        <div class="paper-suggestion-card__head">
                          <NTag :type="suggestionStateType(suggestion)" size="small">{{ suggestionStateLabel(suggestion) }}</NTag>
                          <NTag :type="suggestion.honestyGrade === 'A' ? 'success' : 'warning'" size="small">{{ suggestion.honestyGrade }} 类</NTag>
                        </div>
                        <strong>{{ suggestion.category }} · {{ suggestion.severity || '-' }}</strong>
                        <p>{{ suggestion.statement }}</p>
                        <small>{{ suggestion.honestyReason }} · evidence {{ suggestion.evidenceCount }} · {{ suggestion.track }} · {{ suggestion.status }}</small>
                        <br v-if="suggestionDecisionReason(suggestion)" />
                        <small v-if="suggestionDecisionReason(suggestion)" class="paper-suggestion-decision">{{ suggestionDecisionReason(suggestion) }}</small>
                        <pre v-if="suggestion.patchJson && previewMode === 'advanced'" class="paper-code-preview">{{ prettyJson(suggestion.patchJson) }}</pre>
                      </div>
                    </div>
                  </div>
                </NTabPane>

                <NTabPane name="report" :tab="`报告(${suggestions.length})`">
                  <div class="paper-result-scroll">
                    <div class="paper-report-panel">
                      <div class="paper-panel-heading">
                        <strong>审查报告与 suggested.bib</strong>
                        <NTag size="small" :type="suggestions.length > 0 ? 'success' : 'default'">{{ suggestions.length }} 条建议</NTag>
                      </div>
                      <NEmpty v-if="suggestions.length === 0 && bibliographyCards.length === 0 && citationSlots.length === 0" description="暂无审查报告或推荐文献" />
                      <div v-if="citationSlots.length > 0" class="paper-report-item">
                        <div class="paper-panel-heading"><strong>引言 Citation Slots</strong><span>按引言论证链检索文献</span></div>
                        <div v-for="(slot, slotIndex) in citationSlots" :key="slot.id || slotIndex" class="paper-bib-card">
                          <strong>{{ slot.category || `Slot ${slotIndex + 1}` }}</strong>
                          <p>{{ slot.claim }}</p>
                          <small>需要：{{ slot.citationNeed || 'NEEDS_SUPPORT' }} · 原有引用：{{ Array.isArray(slot.existingCitationKeys) && slot.existingCitationKeys.length ? slot.existingCitationKeys.join(', ') : '尚未识别' }}</small>
                          <div class="paper-bib-card__links">
                            <span v-for="query in (slot.queries || [])" :key="query">{{ query }}</span>
                          </div>
                        </div>
                      </div>
                      <div v-for="suggestion in suggestions" :key="`report-${suggestion.id}`" class="paper-report-item">
                        <div class="paper-report-item__head">
                          <strong>{{ suggestion.severity || 'INFO' }} · {{ suggestion.category }}</strong>
                          <NTag size="small" :type="suggestion.track === 'ADVOCACY' ? 'success' : 'warning'">{{ suggestion.track }}</NTag>
                        </div>
                        <p>{{ suggestion.statement }}</p>
                        <div class="paper-report-evidence">
                          <span v-if="suggestion.evidenceCards.length === 0">无真实 evidence，禁止直接写入论文。</span>
                          <a v-for="card in suggestion.evidenceCards" :key="card.id" :href="card.url || doiUrl(card.doi) || undefined" target="_blank" rel="noreferrer">
                            [card-{{ card.id }}] {{ card.title }}{{ card.publicationYear ? ` (${card.publicationYear})` : '' }}
                          </a>
                        </div>
                      </div>
                      <div v-if="bibliographyCards.length > 0" class="paper-bib-list">
                        <div class="paper-panel-heading"><strong>推荐文献列表</strong><span>请投稿前逐条核验</span></div>
                        <div v-for="card in bibliographyCards" :key="`bib-${card.id}`" class="paper-bib-card">
                          <strong>{{ card.title }}</strong>
                          <small>{{ formatAuthors(card.authors) }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</small>
                          <small v-if="card.relevanceScore != null || card.narrativeRole || card.sourceQuery">
                            {{ card.narrativeRole || 'literature' }} · score {{ card.relevanceScore != null ? card.relevanceScore.toFixed(3) : '-' }} · query {{ card.sourceQuery || '-' }}
                          </small>
                          <div class="paper-bib-card__links">
                            <a v-if="card.doi" :href="doiUrl(card.doi)" target="_blank" rel="noreferrer">DOI: {{ card.doi }}</a>
                            <a v-if="card.url" :href="card.url" target="_blank" rel="noreferrer">URL</a>
                            <a v-if="card.pdfUrl" :href="card.pdfUrl" target="_blank" rel="noreferrer">PDF</a>
                            <span v-if="card.openAlexId">OpenAlex: {{ card.openAlexId }}</span>
                          </div>
                        </div>
                      </div>
                      <NAlert type="warning" title="免责声明">
                        审查报告和 suggested.bib 是 AI 辅助结果。请在投稿前核验每条引用、事实陈述、DOI/URL 与 LaTeX 改动。
                      </NAlert>
                    </div>
                  </div>
                </NTabPane>
              </NTabs>
            </div>
          </NCard>
        </NGridItem>
      </NGrid>
      <NModal
        v-model:show="structureConfirmationVisible"
        preset="card"
        title="Structure confirmation"
        class="paper-structure-modal"
        :mask-closable="false"
        :close-on-esc="false"
      >
        <NAlert type="warning" title="Input required">
          Confirm the detected manuscript structure before the full workflow continues.
        </NAlert>
        <div class="paper-structure-modal__items">
          <div v-for="item in pendingClarifications" :key="`modal-${item.id}`" class="paper-structure-modal__item">
            <strong>{{ clarificationQuestion(item).message || item.type }}</strong>
            <NRadioGroup v-model:value="clarificationAnswers[item.id]">
              <NSpace vertical size="small">
                <NRadio v-for="option in clarificationOptions(item).options" :key="option" :value="option">{{ option }}</NRadio>
              </NSpace>
            </NRadioGroup>
            <NSpace size="small">
              <NButton type="primary" size="small" :loading="clarificationSubmitting" @click="submitClarification(item)">Confirm</NButton>
              <NButton size="small" secondary :disabled="clarificationSubmitting" @click="skipClarification(item)">Keep detected structure</NButton>
            </NSpace>
          </div>
          <NEmpty v-if="pendingClarifications.length === 0" description="Loading structure confirmation items..." />
        </div>
        <template #footer>
          <NButton v-if="pendingClarifications.length > 1" type="primary" secondary block :loading="clarificationSubmitting" @click="keepAllClarifications">
            Confirm all detected structure
          </NButton>
        </template>
      </NModal>
    </div>
  </AppLayout>
</template>

<script setup lang="ts">
import {
  NAlert,
  NButton,
  NButtonGroup,
  NCard,
  NCheckbox,
  NCollapse,
  NCollapseItem,
  NEmpty,
  NForm,
  NFormItem,
  NFormItemGi,
  NGrid,
  NGridItem,
  NInputNumber,
  NModal,
  NProgress,
  NRadio,
  NRadioGroup,
  NSelect,
  NSpace,
  NTabPane,
  NTabs,
  NTag,
} from 'naive-ui';
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
import WorkspaceHero from '@/components/WorkspaceHero.vue';
import {
  answerPaperClarification,
  createPaperTask,
  downloadPaperTask,
  getPaperAnalysis,
  getPaperArtifacts,
  getPaperClarifications,
  getPaperSections,
  getPaperSuggestions,
  getPaperTask,
  getPaperTasks,
  pausePaperTask,
  resumePaperTask,
  updatePaperSectionRevisionStatus as updatePaperSectionRevisionStatusApi,
  updatePaperSectionRole,
  type PaperAnalysisResponse,
  type PaperArtifactResponse,
  type PaperClarificationResponse,
  type PaperSectionResponse,
  type PaperSuggestionResponse,
  type PaperTaskHistoryResponse,
  type PaperTaskResponse,
} from '@/api/paper';
import { cancelTask, getTaskStatus, listTaskEvents, type TaskEventResponse, type TaskStatusResponse } from '@/api/task';
import { ui } from '@/ui';
import { expireAuthSession, isJwtExpired } from '@/auth/session';

const route = useRoute();
const router = useRouter();
const PAPER_TASK_TYPE = 'paper_polish';
const TERMINAL_TASK_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT']);
const ACTIVE_TASK_STATUSES = new Set(['PENDING', 'RUNNING', 'PAUSED', 'WAITING_INPUT', 'CANCEL_REQUESTED', 'CANCELLING']);
const texInputRef = ref<HTMLInputElement | null>(null);
const bibInputRef = ref<HTMLInputElement | null>(null);
const selectedTexFile = ref<File | null>(null);
const selectedBibFile = ref<File | null>(null);
const submitting = ref(false);
const downloading = ref(false);
const currentTask = ref<PaperTaskResponse | null>(null);
const events = ref<PaperTimelineEvent[]>([]);
const clarifications = ref<PaperClarificationResponse[]>([]);
const sections = ref<PaperSectionResponse[]>([]);
const suggestions = ref<PaperSuggestionResponse[]>([]);
const artifacts = ref<PaperArtifactResponse[]>([]);
const analysis = ref<PaperAnalysisResponse | null>(null);
const historyTasks = ref<PaperTaskHistoryResponse[]>([]);
const historyLoading = ref(false);
const downloadingTaskId = ref<number | null>(null);
const previewMode = ref<'basic' | 'advanced'>('basic');
const revisionSuggestionsCollapsed = ref(false);
const evidenceSnippetsCollapsed = ref(false);
const activeReviewTab = ref('clarification');
const activeResultTab = ref('overview');
const sectionRevisionSubmitting = ref<number | null>(null);
const clarificationAnswers = reactive<Record<number, string>>({});
const clarificationSubmitting = ref(false);
const structureConfirmationVisible = ref(false);
const sseStatus = ref<'idle' | 'connecting' | 'connected' | 'closed' | 'error'>('idle');
const taskStatus = ref<TaskStatusResponse | null>(null);
const lastTaskEventId = ref<number | null>(null);
let abortController: AbortController | null = null;
let taskBoardRefreshTimer: number | null = null;

const form = reactive({
  targetLanguage: 'zh' as 'zh' | 'en',
  scoreThreshold: 75,
  maxRounds: 3,
  innerMaxAttempts: 2,
  literatureMinCount: 8,
  literatureCount: 20,
});

const languageOptions = [
  { label: '中文', value: 'zh' },
  { label: 'English', value: 'en' },
];

const sectionRoleOptions = [
  { label: '摘要', value: 'ABSTRACT' },
  { label: '引言', value: 'INTRO' },
  { label: '相关工作', value: 'RELATED_WORK' },
  { label: '方法', value: 'METHOD' },
  { label: '实验', value: 'EXPERIMENTS' },
  { label: '结果', value: 'RESULTS' },
  { label: '讨论', value: 'DISCUSSION' },
  { label: '结论', value: 'CONCLUSION' },
  { label: '参考文献', value: 'REFERENCES' },
  { label: '附录', value: 'APPENDIX' },
  { label: '未知', value: 'UNKNOWN' },
];

const currentTaskId = computed(() => currentTask.value?.id ?? null);
const canPause = computed(() => currentTask.value?.status === 'RUNNING');
const canResume = computed(() => currentTask.value?.status === 'PAUSED');
const canStop = computed(() => {
  if (taskStatus.value) {
    return taskStatus.value.cancellable;
  }
  return ['PENDING', 'RUNNING', 'PAUSED', 'CANCEL_REQUESTED', 'CANCELLING'].includes(currentTask.value?.status || '');
});
const downloadableArtifactTypes = ['polished_tex', 'suggested_bib', 'suggested_bib_novel', 'review_report', 'retrieved_literature_json', 'retrieved_literature_md'];
const activeArtifacts = computed(() => artifacts.value.filter((item) => !item.artifactStatus || item.artifactStatus === 'COMPLETED'));
const hasDownloadableArtifacts = computed(() => activeArtifacts.value.some((item) => downloadableArtifactTypes.includes(item.type)));
const canDownload = computed(() => (Boolean(currentTask.value?.finalObjectKey) || hasDownloadableArtifacts.value) && !downloading.value);
const resultFileText = computed(() => {
  const count = activeArtifacts.value.filter((item) => downloadableArtifactTypes.includes(item.type)).length;
  if (count > 0) return `已生成 ${count} 个产物，可下载 zip`;
  if (currentTask.value?.finalObjectKey) return currentTask.value.finalObjectKey;
  return '尚未生成';
});
const pendingClarifications = computed(() => clarifications.value.filter((item) => item.status === 'PENDING'));
const pendingBlockingClarifications = computed(() => pendingClarifications.value.filter((item) => clarificationQuestion(item).blocking));
const needsStructureConfirmation = computed(() => currentTask.value?.status === 'WAITING_INPUT' || pendingClarifications.value.length > 0);
const acceptedSuggestions = computed(() => suggestions.value.filter((item) => item.status === 'ACCEPTED'));
const suggestedBibArtifacts = computed(() => activeArtifacts.value.filter((item) => item.type === 'suggested_bib' || item.type === 'suggested_bib_novel'));
const polishedTexArtifacts = computed(() => activeArtifacts.value.filter((item) => item.type === 'polished_tex'));
const retrievedLiteratureArtifacts = computed(() => activeArtifacts.value.filter((item) => item.type === 'retrieved_literature_json' || item.type === 'retrieved_literature_md'));
const previewSections = computed(() => sections.value.filter((section) => section.diffJson || section.reviewJson || section.polishStatus));
const bibliographyCards = computed(() => {
  const seen = new Set<number>();
  return suggestions.value.flatMap((suggestion) => suggestion.evidenceCards || []).filter((card) => {
    if (seen.has(card.id)) return false;
    seen.add(card.id);
    return true;
  });
});
const visibleSuggestions = computed(() => suggestions.value);
const literatureSupportCards = computed(() => bibliographyCards.value);
const exportArtifacts = computed(() => activeArtifacts.value.filter((item) => downloadableArtifactTypes.includes(item.type)));
const liveTaskEvents = computed(() => events.value);
const latestProgressEvent = computed(() => [...events.value].reverse().find((event) => hasProgressMeta(event)) || null);
const latestTaskEvent = computed(() => events.value.length > 0 ? events.value[events.value.length - 1] : null);
const workflowSteps = [
  { stage: 'UPLOAD', label: 'Upload', shortLabel: '1' },
  { stage: 'PARSE', label: 'Parse', shortLabel: '2' },
  { stage: 'STRUCTURE_CHECK', label: 'Structure Check', shortLabel: '3' },
  { stage: 'RESEARCH_PROFILE', label: 'Research Profile', shortLabel: '4' },
  { stage: 'RETRIEVE', label: 'Literature Retrieval', shortLabel: '5' },
  { stage: 'GAP_ANALYSIS', label: 'Critique', shortLabel: '6' },
  { stage: 'POLISH', label: 'Section Polish', shortLabel: '7' },
  { stage: 'GLOBAL_REVIEW', label: 'Global Review', shortLabel: '8' },
  { stage: 'ASSEMBLE', label: 'Export', shortLabel: '9' },
] as const;

const legacyStageAlias: Record<string, string> = {
  UPLOAD_RECEIVED: 'UPLOAD',
  SUMMARY: 'PARSE',
  SECTIONS: 'POLISH',
  PROFILE: 'RESEARCH_PROFILE',
  PAPER_REVIEW: 'GAP_ANALYSIS',
  ABSTRACT: 'POLISH',
  REFERENCES: 'ASSEMBLE',
  COMPLETE: 'ASSEMBLE',
};
const progressPercent = computed(() => {
  if (currentTask.value?.status === 'COMPLETED') return 100;
  if (currentTask.value?.status === 'FAILED' || currentTask.value?.status === 'STOPPED' || currentTask.value?.status === 'CANCELLED') return latestProgressEvent.value?.progressPercent ?? 0;
  if (latestProgressEvent.value?.progressPercent != null) return clampProgress(latestProgressEvent.value.progressPercent);
  const index = workflowSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  return index >= 0 ? Math.round((index / Math.max(workflowSteps.length - 1, 1)) * 100) : 0;
});
const normalizedCurrentStage = computed(() => normalizeStage(latestProgressEvent.value?.stage || currentTask.value?.currentStage || null));
const currentStageLabel = computed(() => stageLabel(normalizedCurrentStage.value));
const workflowActivityTitle = computed(() => currentTask.value?.status === 'WAITING_INPUT'
  ? 'Structure confirmation required'
  : currentStageLabel.value);
const workflowActivityDetail = computed(() => {
  const event = latestProgressEvent.value || latestTaskEvent.value;
  const stage = normalizedCurrentStage.value;
  if (stage === 'RETRIEVE' && event?.currentSection != null && event.totalSections) {
    return `Analyzing literature cards ${event.currentSection}/${event.totalSections}${event.sectionTitle ? ` · ${event.sectionTitle}` : ''}`;
  }
  if (stage === 'POLISH' && event?.currentSection != null && event.totalSections) {
    const attempt = event.attempt != null && event.maxAttempts ? ` · round ${event.attempt}/${event.maxAttempts}` : '';
    return `Polishing section ${event.currentSection}/${event.totalSections}${event.sectionTitle ? ` · ${event.sectionTitle}` : ''}${attempt}`;
  }
  if (stage === 'GLOBAL_REVIEW') return 'Checking cross-section logic, transitions, terminology, notation, and formula explanations.';
  if (currentTask.value?.status === 'WAITING_INPUT') return `${pendingClarifications.value.length || 1} structure item(s) require confirmation before processing continues.`;
  return event?.message || `Current stage: ${currentStageLabel.value}`;
});
const sectionProgressText = computed(() => {
  const event = latestProgressEvent.value;
  if (!event?.currentSection || !event.totalSections) return '暂无章节进度';
  const title = event.sectionTitle ? ` · ${event.sectionTitle}` : '';
  return `${event.currentSection}/${event.totalSections}${title}`;
});
const attemptProgressText = computed(() => {
  const event = latestProgressEvent.value;
  if (!event?.attempt || !event.maxAttempts) return '暂无尝试信息';
  return `${event.attempt}/${event.maxAttempts}`;
});
const citationSlots = computed(() => {
  const concept = parseJson<any>(analysis.value?.conceptLadderJson || '{}', {});
  return Array.isArray(concept.citationSlots) ? concept.citationSlots : [];
});
const rawEventLog = computed(() => JSON.stringify(events.value, null, 2));
const activeTaskCards = computed<PaperTaskListItem[]>(() => {
  const items = historyTasks.value
    .filter((task) => ACTIVE_TASK_STATUSES.has(task.status))
    .map((task) => (currentTask.value?.id === task.id ? toTaskListItem(currentTask.value) : toTaskListItem(task)));
  if (currentTask.value && ACTIVE_TASK_STATUSES.has(currentTask.value.status) && !items.some((task) => task.id === currentTask.value?.id)) {
    items.unshift(toTaskListItem(currentTask.value));
  }
  return items.sort((left, right) => {
    const leftWaiting = left.status === 'WAITING_INPUT' ? 1 : 0;
    const rightWaiting = right.status === 'WAITING_INPUT' ? 1 : 0;
    if (leftWaiting !== rightWaiting) return rightWaiting - leftWaiting;
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
  });
});
const sseStatusText = computed(() => {
  if (sseStatus.value === 'connecting') return '连接中';
  if (sseStatus.value === 'connected') return '已连接';
  if (sseStatus.value === 'closed') return '已关闭';
  if (sseStatus.value === 'error') return '异常断开';
  return '未连接';
});

onMounted(async () => {
  await loadHistory();
  taskBoardRefreshTimer = window.setInterval(() => {
    void loadHistoryQuietly();
  }, 15000);
  const taskId = Number(route.query.taskId);
  if (!Number.isNaN(taskId) && taskId > 0) {
    await loadTask(taskId, true);
  } else {
    await restoreInitialTaskSelection();
  }
});

watch(
  () => route.query.taskId,
  async (value) => {
    const taskId = Number(value);
    if (!Number.isNaN(taskId) && taskId > 0 && taskId !== currentTaskId.value) {
      resetTaskEvents();
      await loadTask(taskId, true);
    }
  },
);

watch(needsStructureConfirmation, (needsConfirmation) => {
  if (needsConfirmation) {
    activeReviewTab.value = 'clarification';
    activeResultTab.value = 'clarification';
    structureConfirmationVisible.value = true;
  } else {
    structureConfirmationVisible.value = false;
  }
}, { immediate: true });

onBeforeUnmount(() => {
  abortController?.abort();
  if (taskBoardRefreshTimer != null) {
    window.clearInterval(taskBoardRefreshTimer);
    taskBoardRefreshTimer = null;
  }
});

async function startNewPaperTask() {
  abortController?.abort();
  abortController = null;
  currentTask.value = null;
  taskStatus.value = null;
  resetTaskEvents();
  clarifications.value = [];
  sections.value = [];
  suggestions.value = [];
  artifacts.value = [];
  analysis.value = null;
  selectedTexFile.value = null;
  selectedBibFile.value = null;
  sseStatus.value = 'idle';
  Object.keys(clarificationAnswers).forEach((key) => {
    delete clarificationAnswers[Number(key)];
  });
  if (texInputRef.value) {
    texInputRef.value.value = '';
  }
  if (bibInputRef.value) {
    bibInputRef.value.value = '';
  }
  await router.replace({ path: '/paper' });
  ui.message.info('Ready to start a new paper polish task.');
}

function handleTexFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  selectedTexFile.value = target.files?.[0] || null;
}

function handleBibFileChange(event: Event) {
  const target = event.target as HTMLInputElement;
  selectedBibFile.value = target.files?.[0] || null;
}

async function handleSubmit() {
  if (!selectedTexFile.value) {
    ui.message.warning('请先选择 .tex 主文件');
    return;
  }
  submitting.value = true;
  try {
    const formData = new FormData();
    formData.append('mainTex', selectedTexFile.value);
    if (selectedBibFile.value) {
      formData.append('bibFile', selectedBibFile.value);
    }
    formData.append('targetLanguage', form.targetLanguage);
    formData.append('scoreThreshold', String(form.scoreThreshold));
    formData.append('maxRounds', String(form.maxRounds));
    formData.append('innerMaxAttempts', String(form.innerMaxAttempts));
    formData.append('literatureMinCount', String(form.literatureMinCount));
    formData.append('literatureCount', String(form.literatureCount));
    formData.append('literatureOnly', 'false');
    const { data } = await createPaperTask(formData);
    currentTask.value = data;
    resetTaskEvents();
    clarifications.value = [];
    sections.value = [];
    suggestions.value = [];
    artifacts.value = [];
    analysis.value = null;
    selectedTexFile.value = null;
    selectedBibFile.value = null;
    if (texInputRef.value) {
      texInputRef.value.value = '';
    }
    if (bibInputRef.value) {
      bibInputRef.value.value = '';
    }
    await router.replace({ path: '/paper', query: { taskId: String(data.id) } });
    connectSse();
    await loadHistory();
    ui.message.success('论文任务已创建');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '创建论文任务失败');
  } finally {
    submitting.value = false;
  }
}

async function loadHistory() {
  historyLoading.value = true;
  try {
    const { data } = await getPaperTasks();
    historyTasks.value = data;
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载历史任务失败');
  } finally {
    historyLoading.value = false;
  }
}

async function loadHistoryQuietly() {
  if (historyLoading.value) return;
  try {
    const { data } = await getPaperTasks();
    historyTasks.value = data;
    const latestCurrentTask = currentTaskId.value == null ? null : data.find((item) => item.id === currentTaskId.value);
    if (latestCurrentTask?.status === 'WAITING_INPUT') {
      await loadTask(latestCurrentTask.id, false);
    }
  } catch {
    // Background refresh should not interrupt the user's current paper review work.
  }
}

async function refreshTaskBoard() {
  await loadHistory();
  if (currentTaskId.value) {
    await loadTask(currentTaskId.value, false);
  } else {
    await restoreInitialTaskSelection();
  }
}

async function restoreInitialTaskSelection() {
  const preferredTask = pickPreferredTask(historyTasks.value.map(toTaskListItem));
  if (!preferredTask) return;
  await router.replace({ path: '/paper', query: { taskId: String(preferredTask.id) } });
  await loadTask(preferredTask.id, ACTIVE_TASK_STATUSES.has(preferredTask.status));
}

async function openHistoryTask(taskId: number) {
  await router.replace({ path: '/paper', query: { taskId: String(taskId) } });
  await loadTask(taskId, ACTIVE_TASK_STATUSES.has(historyTasks.value.find((item) => item.id === taskId)?.status || ''));
}

async function downloadHistoryTask(taskId: number) {
  downloadingTaskId.value = taskId;
  try {
    await downloadTaskById(taskId, historyTasks.value.find((item) => item.id === taskId)?.sourceFilename || undefined);
  } finally {
    downloadingTaskId.value = null;
  }
}

async function loadTask(taskId: number, autoConnect = false) {
  try {
    const { data } = await getPaperTask(taskId);
    currentTask.value = data;
    try {
      const unified = await getTaskStatus(taskId, PAPER_TASK_TYPE);
      taskStatus.value = unified.data;
    } catch {
      taskStatus.value = null;
    }
    if (events.value.length === 0) {
      await loadTaskEventHistory(taskId);
    }
    await loadClarificationsAndSections(taskId);
    const effectiveStatus = taskStatus.value?.status || data.status;
    if (autoConnect && ['PENDING', 'RUNNING', 'PAUSED', 'WAITING_INPUT', 'CANCEL_REQUESTED', 'CANCELLING'].includes(effectiveStatus)) {
      connectSse();
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '加载论文任务失败');
  }
}

async function refreshTask() {
  if (!currentTaskId.value) return;
  await loadTask(currentTaskId.value, false);
}

async function focusStructureConfirmation() {
  activeReviewTab.value = 'clarification';
  activeResultTab.value = 'clarification';
  structureConfirmationVisible.value = true;
  await nextTick();
  document.getElementById('paper-structure-confirmation')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function loadClarificationsAndSections(taskId: number) {
  const [clarificationResult, sectionResult, suggestionResult, artifactResult, analysisResult] = await Promise.allSettled([
    getPaperClarifications(taskId),
    getPaperSections(taskId),
    getPaperSuggestions(taskId),
    getPaperArtifacts(taskId),
    getPaperAnalysis(taskId),
  ]);
  if (clarificationResult.status === 'fulfilled') {
    clarifications.value = clarificationResult.value.data;
    for (const item of clarifications.value) {
      if (item.status === 'PENDING' && !clarificationAnswers[item.id]) {
        clarificationAnswers[item.id] = clarificationOptions(item).defaultOption || '保持原样';
      }
    }
  }
  if (sectionResult.status === 'fulfilled') {
    sections.value = sectionResult.value.data;
  }
  if (suggestionResult.status === 'fulfilled') {
    suggestions.value = suggestionResult.value.data;
  }
  if (artifactResult.status === 'fulfilled') {
    artifacts.value = artifactResult.value.data;
  }
  if (analysisResult.status === 'fulfilled') {
    analysis.value = analysisResult.value.data;
  }
}

function connectSse() {
  if (!currentTaskId.value) {
    return;
  }
  abortController?.abort();
  abortController = new AbortController();
  sseStatus.value = 'connecting';
  const token = localStorage.getItem('yanban_access_token');
  if (!token || isJwtExpired(token)) {
    void redirectAfterAuthExpiry();
    return;
  }
  void streamTaskEvents(currentTaskId.value, token, abortController.signal, lastTaskEventId.value);
}

async function streamTaskEvents(taskId: number, token: string, signal: AbortSignal, afterEventId: number | null) {
  try {
    const response = await fetch(`/api/v1/agent/tasks/${PAPER_TASK_TYPE}/${taskId}/events/stream`, {
      headers: {
        Authorization: `Bearer ${token}`,
        ...(afterEventId != null ? { 'Last-Event-ID': String(afterEventId) } : {}),
      },
      signal,
    });
    if (response.status === 401) {
      await redirectAfterAuthExpiry();
      return;
    }
    if (!response.ok || !response.body) {
      throw new Error('SSE 连接失败');
    }
    sseStatus.value = 'connected';
    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const chunks = buffer.split('\n\n');
      buffer = chunks.pop() || '';
      for (const chunk of chunks) {
        const event = parseTaskEventChunk(chunk);
        if (event) {
          appendTaskEvents([event]);
          applyTaskEventToTaskState(event);
          if (event.type !== 'TASK_PROGRESS') {
            await loadTask(taskId, false);
          }
          if (isTerminalTaskEvent(event)) {
            await loadHistory();
            abortController?.abort();
            sseStatus.value = 'closed';
            return;
          }
        }
      }
    }
    sseStatus.value = signal.aborted ? 'closed' : 'closed';
  } catch (error: any) {
    if (error.name !== 'AbortError') {
      sseStatus.value = 'error';
      ui.message.warning(error.message || 'SSE 已断开');
      return;
    }
    sseStatus.value = 'closed';
  }
}

async function redirectAfterAuthExpiry() {
  abortController?.abort();
  sseStatus.value = 'closed';
  expireAuthSession();
  if (route.name !== 'login') {
    await router.replace({ name: 'login', query: { redirect: route.fullPath } });
  }
}

function parseTaskEventChunk(chunk: string): PaperTimelineEvent | null {
  const lines = chunk.split('\n');
  let idText = '';
  let data = '';
  for (const line of lines) {
    if (line.startsWith(':')) {
      continue;
    }
    if (line.startsWith('id:')) {
      idText = line.slice(3).trim();
    }
    if (line.startsWith('data:')) {
      data += line.slice(5).trim();
    }
  }
  if (!data) return null;
  try {
    const parsed = JSON.parse(data) as TaskEventResponse;
    if (idText && parsed.id == null) {
      parsed.id = Number(idText);
    }
    return normalizeTaskEvent(parsed);
  } catch {
    return null;
  }
}

async function handlePause() {
  if (!currentTaskId.value) return;
  await pausePaperTask(currentTaskId.value);
  await refreshTask();
}

async function handleResume() {
  if (!currentTaskId.value) return;
  await resumePaperTask(currentTaskId.value);
  await refreshTask();
  connectSse();
}

async function handleStop() {
  if (!currentTaskId.value) return;
  try {
    const { data } = await cancelTask(currentTaskId.value, {
      taskType: PAPER_TASK_TYPE,
      cancelReason: 'user requested stop',
    });
    if (data.cancelAccepted) {
      ui.message.success(`停止请求已提交 (${data.beforeStatus} -> ${data.afterStatus})`);
    } else {
      ui.message.info(data.message || '任务已处于终态，无法再次停止');
    }
    await refreshTask();
    if (data.cancelAccepted && !TERMINAL_TASK_STATUSES.has(data.afterStatus)) {
      if (sseStatus.value !== 'connected') {
        connectSse();
      }
    } else {
      abortController?.abort();
      sseStatus.value = 'closed';
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '停止请求失败');
  }
}

async function handleDownload() {
  if (!currentTaskId.value) return;
  downloading.value = true;
  try {
    await downloadTaskById(currentTaskId.value, currentTask.value?.sourceFilename || undefined);
  } finally {
    downloading.value = false;
  }
}

async function downloadTaskById(taskId: number, fallbackFilename?: string) {
  try {
    const { data, headers } = await downloadPaperTask(taskId);
    const contentDisposition = String(headers['content-disposition'] || '');
    const matched = contentDisposition.match(/filename=\"?([^\";]+)\"?/i);
    const filename = decodeURIComponent(matched?.[1] || fallbackFilename || 'paper-result.zip');
    const blobUrl = window.URL.createObjectURL(data);
    const anchor = document.createElement('a');
    anchor.href = blobUrl;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.URL.revokeObjectURL(blobUrl);
    ui.message.success('下载已开始');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '下载结果文件失败');
  }
}

function downloadableHistoryArtifacts(task: PaperTaskHistoryResponse) {
  return (task.artifacts || []).filter((item) => downloadableArtifactTypes.includes(item.type));
}

function historyTaskDownloadable(task: PaperTaskHistoryResponse) {
  return Boolean(task.finalObjectKey) || downloadableHistoryArtifacts(task).length > 0;
}

function artifactDisplayName(artifact: PaperArtifactResponse) {
  const labels: Record<string, string> = {
    polished_tex: 'polished.tex',
    suggested_bib: 'suggested.bib',
    suggested_bib_novel: 'suggested-novel.bib',
    review_report: 'review-report.md',
    retrieved_literature_json: 'retrieved-literature.json',
    retrieved_literature_md: 'retrieved-literature.md',
    source_tex: 'source.tex',
    source_bib: 'source.bib',
  };
  let base = labels[artifact.type] || artifact.type;
  if (artifact.metadataJson) {
    try {
      const metadata = JSON.parse(artifact.metadataJson) as { filename?: string };
      if (metadata.filename) base = metadata.filename;
    } catch {
      // Keep the type-based fallback for legacy artifact metadata.
    }
  }
  return artifact.version && artifact.version > 1 ? `${base} v${artifact.version}` : base;
}

function formatFileSize(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MB`;
}

function statusTagType(status: string) {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED' || status === 'STOPPED' || status === 'CANCELLED') return 'error';
  if (status === 'CANCEL_REQUESTED' || status === 'CANCELLING') return 'warning';
  if (status === 'RUNNING' || status === 'PENDING') return 'info';
  if (status === 'PAUSED' || status === 'WAITING_INPUT') return 'warning';
  return 'default';
}

function taskCardTitle(task: PaperTaskListItem) {
  return task.title || task.sourceFilename || `论文任务 #${task.id}`;
}

function taskStatusLabel(status: string) {
  const labels: Record<string, string> = {
    PENDING: '等待中',
    RUNNING: '运行中',
    PAUSED: '已暂停',
    WAITING_INPUT: '等待确认',
    CANCEL_REQUESTED: '等待停止',
    CANCELLING: '停止中',
    COMPLETED: '已完成',
    FAILED: '失败',
    CANCELLED: '已取消',
    TIMED_OUT: '已超时',
  };
  return labels[status] || status;
}

function taskStageLabel(task: PaperTaskListItem) {
  if (task.status === 'WAITING_INPUT') return '结构确认等待处理';
  if (task.status === 'COMPLETED') return '结果已生成';
  if (task.status === 'FAILED') return '任务执行失败';
  return stageLabel(task.currentStage || task.status);
}

function toTaskListItem(task: PaperTaskHistoryResponse | PaperTaskResponse): PaperTaskListItem {
  return {
    id: task.id,
    title: task.title,
    sourceFilename: task.sourceFilename,
    status: task.status,
    currentStage: task.currentStage,
    updatedAt: task.updatedAt,
  };
}

function pickPreferredTask(tasks: PaperTaskListItem[]) {
  const activeTasks = tasks.filter((task) => ACTIVE_TASK_STATUSES.has(task.status));
  if (activeTasks.length === 0) return null;
  return [...activeTasks].sort((left, right) => {
    const leftWaiting = left.status === 'WAITING_INPUT' ? 1 : 0;
    const rightWaiting = right.status === 'WAITING_INPUT' ? 1 : 0;
    if (leftWaiting !== rightWaiting) return rightWaiting - leftWaiting;
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
  })[0];
}

function taskFlowStepClass(task: PaperTaskListItem, stage: string) {
  const currentIndex = taskFlowIndex(task);
  const index = workflowSteps.findIndex((step) => step.stage === stage);
  const terminalComplete = task.status === 'COMPLETED';
  return {
    'paper-running-task__step--done': terminalComplete || (currentIndex >= 0 && index < currentIndex),
    'paper-running-task__step--active': !terminalComplete && currentIndex >= 0 && index === currentIndex,
    'paper-running-task__step--waiting': task.status === 'WAITING_INPUT' && stage === 'STRUCTURE_CHECK',
  };
}

function taskFlowIndex(task: PaperTaskListItem) {
  if (task.status === 'COMPLETED') return workflowSteps.length - 1;
  if (task.status === 'WAITING_INPUT') return workflowSteps.findIndex((step) => step.stage === 'STRUCTURE_CHECK');
  const normalizedStage = normalizeStage(task.currentStage || null);
  if (!normalizedStage) return workflowSteps.findIndex((step) => step.stage === 'UPLOAD');
  return workflowSteps.findIndex((step) => step.stage === normalizedStage);
}

async function submitClarification(item: PaperClarificationResponse) {
  if (!currentTaskId.value) return;
  const option = clarificationAnswers[item.id] || clarificationOptions(item).defaultOption || '保持原样';
  await answerClarificationWithOption(item, option, 'answer');
}

async function skipClarification(item: PaperClarificationResponse) {
  await answerClarificationWithOption(item, clarificationAnswers[item.id] || '跳过', 'skip');
}

async function keepAllClarifications() {
  clarificationSubmitting.value = true;
  try {
    for (const item of pendingClarifications.value) {
      const option = clarificationOptions(item).defaultOption || '保持原样';
      clarificationAnswers[item.id] = option;
      await answerClarificationWithOption(item, option, 'keep', false);
    }
    if (currentTaskId.value) await loadTask(currentTaskId.value, false);
    ui.message.success('已全部保持原样');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '提交结构确认失败');
  } finally {
    clarificationSubmitting.value = false;
  }
}

async function answerClarificationWithOption(item: PaperClarificationResponse, option: string, action: string, manageLoading = true) {
  if (!currentTaskId.value) return;
  if (manageLoading) clarificationSubmitting.value = true;
  try {
    const answerJson = JSON.stringify({ action, option, source: 'paper-page' });
    await answerPaperClarification(currentTaskId.value, item.id, answerJson);
    await loadTask(currentTaskId.value, false);
    if (manageLoading) ui.message.success('结构确认已提交');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '提交结构确认失败');
    throw error;
  } finally {
    if (manageLoading) clarificationSubmitting.value = false;
  }
}

async function handleSectionRoleChange(sectionId: number, role: string) {
  if (!currentTaskId.value) return;
  try {
    await updatePaperSectionRole(currentTaskId.value, sectionId, role);
    await loadClarificationsAndSections(currentTaskId.value);
    ui.message.success('章节角色已更新');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '更新章节角色失败');
  }
}

function suggestionStateLabel(suggestion: PaperSuggestionResponse) {
  const closure = suggestionClosure(suggestion);
  if (closure?.status === 'SUPPORTED') {
    return `Repaired (R${Number(closure.attempts || 1)})`;
  }
  if (closure?.status === 'REPORT_ONLY') {
    return `Report only (R${Number(closure.attempts || 0)})`;
  }
  const decision = suggestionDecision(suggestion);
  if (decision?.verdict === 'PARTIAL') return suggestion.status === 'ACCEPTED' ? 'Auto accepted: supported clause' : 'Critic: partial';
  if (decision?.verdict === 'REJECTED') return 'Critic rejected';
  if (decision?.verdict === 'UNREVIEWED') return 'Review unavailable';
  if (suggestion.status === 'ACCEPTED') return 'Auto accepted';
  if (suggestion.status === 'REJECTED') return 'Not applied';
  return suggestion.applicable ? 'Not auto-applied' : 'Report only';
}

function suggestionStateType(suggestion: PaperSuggestionResponse): 'success' | 'warning' | 'default' {
  const closure = suggestionClosure(suggestion);
  if (closure?.status === 'SUPPORTED') return 'success';
  if (closure?.status === 'REPORT_ONLY') return 'default';
  const decision = suggestionDecision(suggestion);
  if (decision?.verdict === 'PARTIAL' && suggestion.status === 'ACCEPTED') return 'success';
  if (decision?.verdict === 'PARTIAL' || decision?.verdict === 'UNREVIEWED') return 'warning';
  if (decision?.verdict === 'REJECTED') return 'default';
  if (suggestion.status === 'ACCEPTED') return 'success';
  return suggestion.applicable ? 'warning' : 'default';
}

function suggestionDecision(suggestion: PaperSuggestionResponse): Record<string, any> | null {
  if (!suggestion.patchJson) return null;
  try {
    const patch = JSON.parse(suggestion.patchJson);
    return patch.citationClosure || patch.citationCritic || patch.applicationDecision || null;
  } catch {
    return null;
  }
}

function suggestionClosure(suggestion: PaperSuggestionResponse): Record<string, any> | null {
  if (!suggestion.patchJson) return null;
  try {
    return JSON.parse(suggestion.patchJson).citationClosure || null;
  } catch {
    return null;
  }
}

function suggestionDecisionReason(suggestion: PaperSuggestionResponse) {
  const decision = suggestionDecision(suggestion);
  return typeof decision?.reason === 'string' ? decision.reason : '';
}

async function updateSectionRevisionStatus(section: PaperSectionResponse, status: string) {
  if (!currentTaskId.value) return;
  sectionRevisionSubmitting.value = section.id;
  try {
    await updatePaperSectionRevisionStatusApi(currentTaskId.value, section.id, status);
    await loadClarificationsAndSections(currentTaskId.value);
    ui.message.success(status === 'ACCEPTED' ? '已采纳章节润色' : status === 'REJECTED' ? '已拒绝章节润色' : '章节润色已标记为待处理');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '更新章节采纳状态失败');
  } finally {
    sectionRevisionSubmitting.value = null;
  }
}

function prettyJson(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function readableDiff(raw: string) {
  try {
    const parsed = JSON.parse(raw);
    if (typeof parsed.unifiedDiff === 'string' && parsed.unifiedDiff.trim()) {
      return parsed.unifiedDiff;
    }
    if (Array.isArray(parsed.hunks)) {
      return parsed.hunks
        .flatMap((hunk: any) => Array.isArray(hunk.lines) ? hunk.lines : [])
        .map((line: any) => `${line.type === 'ADD' ? '+' : line.type === 'DELETE' ? '-' : ' '}${line.text ?? ''}`)
        .join('\n');
    }
    return JSON.stringify(parsed, null, 2);
  } catch {
    return raw;
  }
}

function formatAuthors(authors: string | null) {
  if (!authors?.trim()) return 'Unknown authors';
  try {
    const parsed = JSON.parse(authors);
    if (Array.isArray(parsed)) {
      const names = parsed.map((author) => String(author).trim()).filter(Boolean);
      return names.length > 0 ? names.join(', ') : 'Unknown authors';
    }
  } catch {
    // Some legacy cards store authors as display-ready plain text.
  }
  return authors.trim();
}

function doiUrl(doi: string | null) {
  if (!doi) return '';
  const normalized = doi.replace(/^https?:\/\/(dx\.)?doi\.org\//i, '').trim();
  return normalized ? `https://doi.org/${normalized}` : '';
}

function clarificationQuestion(item: PaperClarificationResponse) {
  return parseJson<{ message?: string; blocking?: boolean; relatedSectionOrderIndex?: number }>(item.questionJson, {});
}

function clarificationOptions(item: PaperClarificationResponse) {
  return parseJson<{ options: string[]; defaultOption: string }>(item.optionsJson || '', { options: ['保持原样'], defaultOption: '保持原样' });
}

function answeredOption(item: PaperClarificationResponse) {
  const answer = parseJson<{ option?: string; action?: string }>(item.userAnswerJson || '', {});
  if (answer.option) return answer.option;
  if (answer.action === 'skip') return '跳过';
  return item.userAnswerJson || '-';
}

function parseJson<T>(raw: string, fallback: T): T {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function formatConfidence(value: number | null) {
  if (value == null) return '-';
  return `${Math.round(value * 100)}%`;
}

function hasProgressMeta(event: PaperTimelineEvent) {
  return event.progressPercent != null || event.currentSection != null || event.attempt != null || Boolean(event.stage);
}

function normalizeStage(stage: string | null) {
  if (!stage) return null;
  return legacyStageAlias[stage] || stage;
}

function stageLabel(stage: string | null) {
  const normalized = normalizeStage(stage);
  if (normalized === 'GLOBAL_REVIEW') return '全文一致性审查';
  const labels: Record<string, string> = {
    UPLOAD: '上传',
    PARSE: '解析 LaTeX',
    STRUCTURE_CHECK: '结构确认',
    RESEARCH_PROFILE: '研究画像',
    RETRIEVE: '文献检索',
    GAP_ANALYSIS: 'Gap 分析',
    POLISH: '分章润色',
    ASSEMBLE: '三件套组装',
    COMPLETE: '完成',
    CANCEL_REQUESTED: '等待取消',
    CANCELLING: '正在取消',
    CANCELLED: '已取消',
    STOPPED: '已停止',
    FAILED: '失败',
    PENDING: '等待中',
    RUNNING: '运行中',
    PAUSED: '已暂停',
    WAITING_INPUT: '等待确认',
    TIMED_OUT: '已超时',
  };
  return normalized ? labels[normalized] || normalized : '-';
}

function eventDisplayType(type: string) {
  const labels: Record<string, string> = {
    TASK_CREATED: '任务已创建',
    STAGE_CHANGED: '阶段变更',
    TASK_PAUSED: '已暂停',
    TASK_RESUMED: '继续执行',
    TASK_CANCEL_REQUESTED: '已提交停止',
    TASK_CANCELLING: '正在安全停止',
    TASK_CANCELLED: '已取消',
    TASK_COMPLETED: '已完成',
    TASK_FAILED: '执行失败',
    ARTIFACT_MARKED_PARTIAL: '产物标记为部分结果',
  };
  return labels[type] || type;
}

function eventProgressHint(event: PaperTimelineEvent) {
  const parts: string[] = [];
  if (event.currentSection && event.totalSections) {
    parts.push(`章节 ${event.currentSection}/${event.totalSections}`);
  }
  if (event.sectionTitle) {
    parts.push(event.sectionTitle);
  }
  if (event.attempt && event.maxAttempts) {
    parts.push(`尝试 ${event.attempt}/${event.maxAttempts}`);
  }
  if (event.progressPercent != null) {
    parts.push(`进度 ${clampProgress(event.progressPercent)}%`);
  }
  return parts.join(' · ');
}

function stageChipClass(stage: string) {
  const activeIndex = workflowSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  const index = workflowSteps.findIndex((step) => step.stage === stage);
  return {
    'paper-stage-chip--done': activeIndex >= 0 && index < activeIndex,
    'paper-stage-chip--active': activeIndex >= 0 && index === activeIndex,
  };
}

function workflowStepClass(stage: string) {
  if (stage === 'UPLOAD') {
    return {
      'paper-workflow-step-v2--done': Boolean(currentTask.value || selectedTexFile.value),
      'paper-stage-chip--active': !currentTask.value && Boolean(selectedTexFile.value),
    };
  }
  if (stage === 'ASSEMBLE' && currentTask.value?.status === 'COMPLETED') {
    return { 'paper-stage-chip--done': true };
  }
  return stageChipClass(stage);
}

function workflowStepDetail(stage: string) {
  const activeIndex = workflowSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  const index = workflowSteps.findIndex((step) => step.stage === stage);
  const done = currentTask.value?.status === 'COMPLETED' || (activeIndex >= 0 && index < activeIndex);
  if (stage === 'UPLOAD') return currentTask.value || selectedTexFile.value ? 'Ready' : 'Pending';
  if (stage === 'STRUCTURE_CHECK') {
    if (needsStructureConfirmation.value) return `${pendingClarifications.value.length || 1} pending`;
    return done ? 'Confirmed' : activeIndex === index ? 'Checking' : 'Pending';
  }
  if (stage === 'RETRIEVE') {
    const event = latestProgressEvent.value;
    if (activeIndex === index && event?.currentSection != null && event.totalSections) return `${event.currentSection}/${event.totalSections} cards`;
    return done ? `${bibliographyCards.value.length} selected` : activeIndex === index ? 'Searching' : 'Pending';
  }
  if (stage === 'GAP_ANALYSIS') return done ? `${suggestions.value.length} suggestions` : activeIndex === index ? 'Reviewing' : 'Pending';
  if (stage === 'POLISH') {
    const polished = sections.value.filter((section) => Boolean(section.polishStatus)).length;
    const event = latestProgressEvent.value;
    if (activeIndex === index && event?.currentSection != null && event.totalSections) {
      return `${event.currentSection}/${event.totalSections}`;
    }
    return done && sections.value.length > 0 ? `${polished}/${sections.value.length}` : 'Pending';
  }
  if (stage === 'GLOBAL_REVIEW') return done ? 'Reviewed' : activeIndex === index ? 'Reviewing' : 'Pending';
  if (stage === 'ASSEMBLE') return canDownload.value ? 'Ready' : activeIndex === index ? 'Generating files' : 'Pending';
  return done ? 'Completed' : activeIndex === index ? 'In progress' : 'Pending';
}

function clampProgress(value: number) {
  return Math.max(0, Math.min(100, Math.round(value)));
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}

type PaperTimelineEvent = {
  id: number;
  taskType: string;
  taskId: number;
  userId: number;
  type: string;
  stage: string | null;
  status: string;
  message: string;
  payloadJson: string | null;
  timestamp: string;
  progressPercent: number | null;
  currentSection: number | null;
  totalSections: number | null;
  sectionTitle: string | null;
  attempt: number | null;
  maxAttempts: number | null;
};

type PaperTaskListItem = {
  id: number;
  title: string;
  sourceFilename: string | null;
  status: string;
  currentStage: string | null;
  updatedAt: string;
};

async function loadTaskEventHistory(taskId: number) {
  try {
    const { data } = await listTaskEvents(taskId, PAPER_TASK_TYPE);
    appendTaskEvents(data.map(normalizeTaskEvent));
  } catch {
    // Keep the page usable even if the unified event history endpoint is temporarily unavailable.
  }
}

function resetTaskEvents() {
  events.value = [];
  lastTaskEventId.value = null;
}

function appendTaskEvents(incoming: PaperTimelineEvent[]) {
  if (incoming.length === 0) {
    return;
  }
  const merged = new Map<number, PaperTimelineEvent>();
  for (const event of events.value) {
    merged.set(event.id, event);
  }
  for (const event of incoming) {
    const existing = merged.get(event.id);
    if (!existing || event.timestamp >= existing.timestamp) {
      merged.set(event.id, event);
    }
  }
  events.value = Array.from(merged.values()).sort((left, right) => left.id - right.id);
  lastTaskEventId.value = events.value.length > 0 ? events.value[events.value.length - 1].id : null;
}

function applyTaskEventToTaskState(event: PaperTimelineEvent) {
  if (currentTask.value && currentTask.value.id === event.taskId) {
    currentTask.value = {
      ...currentTask.value,
      status: event.status || currentTask.value.status,
      currentStage: event.stage || currentTask.value.currentStage,
      errorMessage: event.status === 'FAILED' ? event.message : currentTask.value.errorMessage,
    };
  }
  if (taskStatus.value && taskStatus.value.taskId === event.taskId) {
    taskStatus.value = {
      ...taskStatus.value,
      status: event.status || taskStatus.value.status,
      currentStage: event.stage || taskStatus.value.currentStage,
      terminal: TERMINAL_TASK_STATUSES.has(event.status),
      cancellable: !TERMINAL_TASK_STATUSES.has(event.status) && !['CANCEL_REQUESTED', 'CANCELLING'].includes(event.status),
    };
  }
}

function normalizeTaskEvent(event: TaskEventResponse): PaperTimelineEvent {
  const payload = parseJson<Record<string, unknown>>(event.payloadJson || '', {});
  return {
    id: event.id,
    taskType: event.taskType,
    taskId: event.taskId,
    userId: event.userId,
    type: event.eventType,
    stage: event.stage,
    status: event.status,
    message: event.message || event.eventType,
    payloadJson: event.payloadJson,
    timestamp: event.createdAt,
    progressPercent: numericPayloadValue(payload.progressPercent),
    currentSection: numericPayloadValue(payload.currentSection),
    totalSections: numericPayloadValue(payload.totalSections),
    sectionTitle: stringPayloadValue(payload.sectionTitle),
    attempt: numericPayloadValue(payload.attempt),
    maxAttempts: numericPayloadValue(payload.maxAttempts),
  };
}

function isTerminalTaskEvent(event: PaperTimelineEvent) {
  return TERMINAL_TASK_STATUSES.has(event.status);
}

function numericPayloadValue(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function stringPayloadValue(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : null;
}
</script>
