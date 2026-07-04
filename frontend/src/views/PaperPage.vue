<template>
  <AppLayout>
    <div class="paper-page workbench-page scholar-page scholar-page--paper">
      <section class="workbench-hero">
        <div>
          <div class="workbench-kicker">Paper workflow</div>
          <h1>LaTeX 论文处理台</h1>
          <p>上传 LaTeX 主文件（.tex）与可选 .bib 后订阅实时事件，处理完成即可下载三件套结果。</p>
        </div>
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
      </section>

      <section class="paper-polish-hero">
        <div>
          <div class="workbench-kicker">Academic Writing</div>
          <h1>Paper Polish Workspace</h1>
          <p>Revise manuscripts with retrieval-backed critique, citation support, live task events, and export-ready artifacts.</p>
        </div>
        <NSpace align="center">
          <NButton secondary @click="startNewPaperTask">New polish</NButton>
          <NButton secondary :loading="historyLoading" @click="loadHistory">Save draft</NButton>
          <NButton type="primary" :loading="submitting" @click="handleSubmit">Run Workflow</NButton>
        </NSpace>
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
                      <NFormItemGi span="24 m:8" label="Max rounds">
                        <NInputNumber v-model:value="form.maxRounds" :min="1" :max="20" style="width: 100%" />
                      </NFormItemGi>
                      <NFormItemGi span="24 m:8" label="Section attempts">
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
                  <div class="paper-literature-switch">
                    <div>
                      <strong>Literature-only mode</strong>
                      <span>Skip full rewrite and only generate citation recommendations.</span>
                    </div>
                    <NSwitch v-model:value="form.literatureOnly" />
                  </div>
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
                    <div><span>Mode</span><strong>{{ form.literatureOnly ? 'Literature recommendation' : 'Full paper polish' }}</strong></div>
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
              <div class="paper-workflow-step-v2" :class="{ 'paper-workflow-step-v2--done': currentTask || selectedTexFile }">
                <span>1</span><strong>Upload</strong><small>{{ currentTask || selectedTexFile ? 'Ready' : 'Pending' }}</small>
              </div>
              <div class="paper-workflow-step-v2" :class="stageChipClass('PARSE')">
                <span>2</span><strong>Parse</strong><small>{{ stageLabel('PARSE') }}</small>
              </div>
              <div class="paper-workflow-step-v2" :class="stageChipClass('RETRIEVE')">
                <span>3</span><strong>Literature Retrieval</strong><small>{{ retrievedLiteratureArtifacts.length }} artifacts</small>
              </div>
              <div class="paper-workflow-step-v2" :class="stageChipClass('GAP_ANALYSIS')">
                <span>4</span><strong>Critique</strong><small>{{ suggestions.length }} suggestions</small>
              </div>
              <div class="paper-workflow-step-v2" :class="stageChipClass('POLISH')">
                <span>5</span><strong>Rewrite</strong><small>{{ acceptedSuggestions.length }} accepted</small>
              </div>
              <div class="paper-workflow-step-v2" :class="{ 'paper-workflow-step-v2--done': canDownload }">
                <span>6</span><strong>Export</strong><small>{{ canDownload ? 'Ready' : 'Pending' }}</small>
              </div>
            </div>
          </NCard>

          <NCard class="workbench-card scholar-card paper-polish-card" :bordered="false">
            <template #header>
              <div class="section-title">Revision Suggestions</div>
            </template>
            <template #header-extra>
              <NTag type="info" round>{{ suggestions.length }} total</NTag>
            </template>

            <NEmpty v-if="suggestions.length === 0" description="No revision suggestions yet. Run a workflow or open a completed task." />
            <div v-else class="paper-suggestion-table-v2">
              <div class="paper-suggestion-table-v2__head">
                <span>Suggestion</span>
                <span>Evidence</span>
                <span>Severity</span>
                <span>State</span>
                <span>Actions</span>
              </div>
              <article v-for="suggestion in visibleSuggestions" :key="suggestion.id" class="paper-suggestion-row-v2">
                <div>
                  <strong>{{ suggestion.category }}</strong>
                  <p>{{ suggestion.statement }}</p>
                </div>
                <span>{{ suggestion.evidenceCount }} evidence cards</span>
                <NTag :type="suggestion.severity === 'HIGH' ? 'error' : suggestion.severity === 'LOW' ? 'success' : 'warning'" size="small">
                  {{ suggestion.severity || 'Info' }}
                </NTag>
                <NTag :type="suggestion.status === 'ACCEPTED' ? 'success' : suggestion.status === 'REJECTED' ? 'error' : 'info'" size="small">
                  {{ suggestion.status }}
                </NTag>
                <NSpace size="small">
                  <NCheckbox
                    :checked="suggestion.status === 'ACCEPTED'"
                    :disabled="!canAcceptSuggestion(suggestion) || suggestionSubmitting === suggestion.id"
                    @update:checked="(checked) => handleSuggestionChecked(suggestion, checked)"
                  >
                    Accept
                  </NCheckbox>
                  <NButton v-if="suggestion.status !== 'REJECTED'" size="tiny" tertiary @click="updateSuggestionStatus(suggestion, 'REJECTED')">
                    Reject
                  </NButton>
                </NSpace>
              </article>
            </div>
          </NCard>

          <NCard class="workbench-card scholar-card paper-polish-card" :bordered="false">
            <template #header>
              <div class="section-title">Evidence Snippets</div>
            </template>
            <div v-if="literatureSupportCards.length > 0" class="paper-evidence-grid-v2">
              <article v-for="card in literatureSupportCards" :key="card.id" class="paper-evidence-card-v2">
                <strong>{{ card.title }}</strong>
                <span>{{ card.authors || 'Unknown authors' }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</span>
                <p>{{ card.citationCount == null ? 'Citation metadata unavailable.' : `${card.citationCount} citations found in metadata.` }}</p>
                <div>
                  <NTag size="small" type="success">Evidence</NTag>
                  <a v-if="card.url || card.doi" :href="card.url || doiUrl(card.doi)" target="_blank" rel="noreferrer">View source</a>
                </div>
              </article>
            </div>
            <NEmpty v-else description="No evidence snippets yet." />
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
                  <span>{{ card.authors || 'Unknown authors' }} · {{ card.publicationYear || '-' }}</span>
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

      <NCard class="workbench-card scholar-card paper-polish-card paper-review-workspace-v2" :bordered="false">
        <template #header>
          <div class="section-title">Review Workspace</div>
        </template>
        <NTabs type="segment" animated>
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
                <pre v-if="section.diffJson" class="paper-code-preview">{{ prettyJson(section.diffJson) }}</pre>
                <pre v-else-if="section.reviewJson" class="paper-code-preview">{{ prettyJson(section.reviewJson) }}</pre>
              </div>
              <div v-for="suggestion in suggestions" :key="suggestion.id" class="paper-suggestion-card" :class="`paper-suggestion-card--${suggestion.honestyGrade}`">
                <strong>{{ suggestion.category }} · {{ suggestion.severity || '-' }}</strong>
                <p>{{ suggestion.statement }}</p>
                <small>{{ suggestion.honestyReason }} · evidence {{ suggestion.evidenceCount }} · {{ suggestion.track }} · {{ suggestion.status }}</small>
                <pre v-if="suggestion.patchJson && previewMode === 'advanced'" class="paper-code-preview">{{ prettyJson(suggestion.patchJson) }}</pre>
              </div>
              <NEmpty v-if="suggestions.length === 0 && previewSections.length === 0" description="No preview results yet." />
            </div>
          </NTabPane>

          <NTabPane name="report" :tab="`Report (${bibliographyCards.length})`">
            <div class="paper-report-panel">
              <div v-for="card in bibliographyCards" :key="`bib-${card.id}`" class="paper-bib-card">
                <strong>{{ card.title }}</strong>
                <small>{{ card.authors || 'Unknown authors' }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</small>
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
                  <NFormItemGi span="2" label="仅推荐文献（跳过 Gap 分析、章节润色和全文改写）">
                    <NSwitch v-model:value="form.literatureOnly" />
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
                  <div v-for="step in stageSteps" :key="step.stage" class="paper-stage-chip" :class="stageChipClass(step.stage)">
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

              <NTabs type="segment" animated class="paper-result-tabs" pane-class="paper-result-pane">
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
                        阻塞类问题必须回答。默认选择“保持原样”，避免误重构论文结构。
                      </NAlert>
                      <div v-for="item in clarifications" :key="item.id" class="paper-clarification-item">
                        <div class="paper-clarification-item__title">
                          <span>{{ clarificationQuestion(item).message || item.type }}</span>
                          <NTag size="small" :type="clarificationQuestion(item).blocking ? 'error' : 'info'">
                            {{ clarificationQuestion(item).blocking ? '必须答' : '可跳过' }}
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
                        <NButton :type="previewMode === 'basic' ? 'primary' : 'default'" size="small" @click="previewMode = 'basic'">基础版：只推荐</NButton>
                        <NButton :type="previewMode === 'advanced' ? 'primary' : 'default'" size="small" @click="previewMode = 'advanced'">进阶版：改原文 + 补 cite</NButton>
                      </NButtonGroup>
                      <NAlert :type="previewMode === 'basic' ? 'info' : 'warning'" :title="previewMode === 'basic' ? '基础版预览' : '进阶版预览'">
                        {{ previewMode === 'basic' ? '仅展示建议、review 与 suggested.bib，不直接改写原文。' : '只会采纳 A 类且 evidence 真实可追溯的候选补丁；B 类仅作为骨架/批评展示。' }}
                      </NAlert>
                      <NEmpty v-if="suggestions.length === 0 && previewSections.length === 0" description="暂无 diff 或建议结果" />
                      <div v-for="section in previewSections" :key="`preview-${section.id}`" class="paper-diff-card">
                        <div class="paper-diff-card__title">{{ section.orderIndex + 1 }}. {{ section.title }} · {{ section.polishStatus || 'NOT_POLISHED' }}</div>
                        <pre v-if="section.diffJson" class="paper-code-preview">{{ prettyJson(section.diffJson) }}</pre>
                        <pre v-else-if="section.reviewJson" class="paper-code-preview">{{ prettyJson(section.reviewJson) }}</pre>
                      </div>
                      <div v-for="suggestion in suggestions" :key="suggestion.id" class="paper-suggestion-card" :class="`paper-suggestion-card--${suggestion.honestyGrade}`">
                        <div class="paper-suggestion-card__head">
                          <NCheckbox :checked="suggestion.status === 'ACCEPTED'" :disabled="!canAcceptSuggestion(suggestion) || suggestionSubmitting === suggestion.id" @update:checked="(checked) => handleSuggestionChecked(suggestion, checked)">
                            采纳
                          </NCheckbox>
                          <NTag :type="suggestion.honestyGrade === 'A' ? 'success' : 'warning'" size="small">{{ suggestion.honestyGrade }} 类</NTag>
                        </div>
                        <strong>{{ suggestion.category }} · {{ suggestion.severity || '-' }}</strong>
                        <p>{{ suggestion.statement }}</p>
                        <small>{{ suggestion.honestyReason }} · evidence {{ suggestion.evidenceCount }} · {{ suggestion.track }} · {{ suggestion.status }}</small>
                        <pre v-if="suggestion.patchJson && previewMode === 'advanced'" class="paper-code-preview">{{ prettyJson(suggestion.patchJson) }}</pre>
                        <NButton v-if="suggestion.status !== 'REJECTED'" size="tiny" tertiary @click="updateSuggestionStatus(suggestion, 'REJECTED')">拒绝</NButton>
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
                          <small>需要：{{ slot.citationNeed || 'NEEDS_SUPPORT' }} · 原有引用：{{ Array.isArray(slot.existingCitationKeys) && slot.existingCitationKeys.length ? slot.existingCitationKeys.join(', ') : '无/未识别' }}</small>
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
                          <small>{{ card.authors || 'Unknown authors' }} · {{ card.publicationYear || '-' }} · {{ card.venue || '-' }}</small>
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
  NProgress,
  NRadio,
  NRadioGroup,
  NSelect,
  NSpace,
  NSwitch,
  NTabPane,
  NTabs,
  NTag,
} from 'naive-ui';
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppLayout from '@/components/AppLayout.vue';
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
  stopPaperTask,
  updatePaperSectionRole,
  updatePaperSuggestionStatus,
  type PaperAnalysisResponse,
  type PaperArtifactResponse,
  type PaperClarificationResponse,
  type PaperSectionResponse,
  type PaperSuggestionResponse,
  type PaperSseEvent,
  type PaperTaskHistoryResponse,
  type PaperTaskResponse,
} from '@/api/paper';
import { ui } from '@/ui';

const route = useRoute();
const router = useRouter();
const texInputRef = ref<HTMLInputElement | null>(null);
const bibInputRef = ref<HTMLInputElement | null>(null);
const selectedTexFile = ref<File | null>(null);
const selectedBibFile = ref<File | null>(null);
const submitting = ref(false);
const downloading = ref(false);
const currentTask = ref<PaperTaskResponse | null>(null);
const events = ref<PaperSseEvent[]>([]);
const clarifications = ref<PaperClarificationResponse[]>([]);
const sections = ref<PaperSectionResponse[]>([]);
const suggestions = ref<PaperSuggestionResponse[]>([]);
const artifacts = ref<PaperArtifactResponse[]>([]);
const analysis = ref<PaperAnalysisResponse | null>(null);
const historyTasks = ref<PaperTaskHistoryResponse[]>([]);
const historyLoading = ref(false);
const downloadingTaskId = ref<number | null>(null);
const previewMode = ref<'basic' | 'advanced'>('basic');
const suggestionSubmitting = ref<number | null>(null);
const clarificationAnswers = reactive<Record<number, string>>({});
const clarificationSubmitting = ref(false);
const sseStatus = ref<'idle' | 'connecting' | 'connected' | 'closed' | 'error'>('idle');
let abortController: AbortController | null = null;

const form = reactive({
  targetLanguage: 'zh' as 'zh' | 'en',
  scoreThreshold: 75,
  maxRounds: 3,
  innerMaxAttempts: 2,
  literatureMinCount: 8,
  literatureCount: 20,
  literatureOnly: true,
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
const canStop = computed(() => ['PENDING', 'RUNNING', 'PAUSED'].includes(currentTask.value?.status || ''));
const downloadableArtifactTypes = ['polished_tex', 'suggested_bib', 'suggested_bib_novel', 'review_report', 'retrieved_literature_json', 'retrieved_literature_md'];
const hasDownloadableArtifacts = computed(() => artifacts.value.some((item) => downloadableArtifactTypes.includes(item.type)));
const canDownload = computed(() => (Boolean(currentTask.value?.finalObjectKey) || hasDownloadableArtifacts.value) && !downloading.value);
const resultFileText = computed(() => {
  const count = artifacts.value.filter((item) => downloadableArtifactTypes.includes(item.type)).length;
  if (count > 0) return `已生成 ${count} 个产物，可下载 zip`;
  if (currentTask.value?.finalObjectKey) return currentTask.value.finalObjectKey;
  return '尚未生成';
});
const pendingClarifications = computed(() => clarifications.value.filter((item) => item.status === 'PENDING'));
const pendingBlockingClarifications = computed(() => pendingClarifications.value.filter((item) => clarificationQuestion(item).blocking));
const acceptedSuggestions = computed(() => suggestions.value.filter((item) => item.status === 'ACCEPTED'));
const suggestedBibArtifacts = computed(() => artifacts.value.filter((item) => item.type === 'suggested_bib' || item.type === 'suggested_bib_novel'));
const polishedTexArtifacts = computed(() => artifacts.value.filter((item) => item.type === 'polished_tex'));
const retrievedLiteratureArtifacts = computed(() => artifacts.value.filter((item) => item.type === 'retrieved_literature_json' || item.type === 'retrieved_literature_md'));
const previewSections = computed(() => sections.value.filter((section) => section.diffJson || section.reviewJson || section.polishStatus));
const bibliographyCards = computed(() => {
  const seen = new Set<number>();
  return suggestions.value.flatMap((suggestion) => suggestion.evidenceCards || []).filter((card) => {
    if (seen.has(card.id)) return false;
    seen.add(card.id);
    return true;
  });
});
const visibleSuggestions = computed(() => suggestions.value.slice(0, 5));
const literatureSupportCards = computed(() => bibliographyCards.value);
const exportArtifacts = computed(() => artifacts.value.filter((item) => downloadableArtifactTypes.includes(item.type)));
const liveTaskEvents = computed(() => events.value);
const latestProgressEvent = computed(() => [...events.value].reverse().find((event) => hasProgressMeta(event)) || null);
const stageSteps = [
  { stage: 'PARSE', label: '解析' },
  { stage: 'STRUCTURE_CHECK', label: '结构确认' },
  { stage: 'RESEARCH_PROFILE', label: '画像' },
  { stage: 'RETRIEVE', label: '文献' },
  { stage: 'GAP_ANALYSIS', label: 'Gap' },
  { stage: 'POLISH', label: '润色' },
  { stage: 'ASSEMBLE', label: '组装' },
  { stage: 'COMPLETE', label: '完成' },
];
const legacyStageAlias: Record<string, string> = {
  SUMMARY: 'PARSE',
  SECTIONS: 'POLISH',
  PAPER_REVIEW: 'GAP_ANALYSIS',
  ABSTRACT: 'POLISH',
  REFERENCES: 'ASSEMBLE',
};
const progressPercent = computed(() => {
  if (currentTask.value?.status === 'COMPLETED') return 100;
  if (currentTask.value?.status === 'FAILED' || currentTask.value?.status === 'STOPPED') return latestProgressEvent.value?.progressPercent ?? 0;
  if (latestProgressEvent.value?.progressPercent != null) return clampProgress(latestProgressEvent.value.progressPercent);
  const index = stageSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  return index >= 0 ? Math.round((index / Math.max(stageSteps.length - 1, 1)) * 100) : 0;
});
const normalizedCurrentStage = computed(() => normalizeStage(latestProgressEvent.value?.stage || currentTask.value?.currentStage || null));
const currentStageLabel = computed(() => stageLabel(currentTask.value?.currentStage || latestProgressEvent.value?.stage || null));
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
const sseStatusText = computed(() => {
  if (sseStatus.value === 'connecting') return '连接中';
  if (sseStatus.value === 'connected') return '已连接';
  if (sseStatus.value === 'closed') return '已关闭';
  if (sseStatus.value === 'error') return '异常断开';
  return '未连接';
});

onMounted(async () => {
  await loadHistory();
  const taskId = Number(route.query.taskId);
  if (!Number.isNaN(taskId) && taskId > 0) {
    await loadTask(taskId, true);
  }
});

watch(
  () => route.query.taskId,
  async (value) => {
    const taskId = Number(value);
    if (!Number.isNaN(taskId) && taskId > 0 && taskId !== currentTaskId.value) {
      events.value = [];
      await loadTask(taskId, true);
    }
  },
);

onBeforeUnmount(() => {
  abortController?.abort();
});

async function startNewPaperTask() {
  abortController?.abort();
  abortController = null;
  currentTask.value = null;
  events.value = [];
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
    formData.append('literatureOnly', String(form.literatureOnly));
    const { data } = await createPaperTask(formData);
    currentTask.value = data;
    events.value = [];
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

async function openHistoryTask(taskId: number) {
  await router.replace({ path: '/paper', query: { taskId: String(taskId) } });
  await loadTask(taskId, ['PENDING', 'RUNNING', 'PAUSED', 'WAITING_INPUT'].includes(historyTasks.value.find((item) => item.id === taskId)?.status || ''));
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
    await loadClarificationsAndSections(taskId);
    if (autoConnect && ['PENDING', 'RUNNING', 'PAUSED', 'WAITING_INPUT'].includes(data.status)) {
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
  if (!token) {
    sseStatus.value = 'error';
    ui.message.error('未登录');
    return;
  }
  void streamPaperEvents(currentTaskId.value, token, abortController.signal);
}

async function streamPaperEvents(taskId: number, token: string, signal: AbortSignal) {
  try {
    const response = await fetch(`/api/v1/paper/events?taskId=${taskId}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal,
    });
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
        const event = parseSseChunk(chunk);
        if (event) {
          events.value.push(event);
          await loadTask(taskId, false);
          if (['clarification_needed', 'clarification_resolved'].includes(event.type)) {
            await loadClarificationsAndSections(taskId);
          }
          if (['complete', 'error', 'paused'].includes(event.type)) {
            await loadHistory();
            abortController?.abort();
            sseStatus.value = 'closed';
            return;
          }
        }
      }
    }
  } catch (error: any) {
    if (error.name !== 'AbortError') {
      sseStatus.value = 'error';
      ui.message.warning(error.message || 'SSE 已断开');
      return;
    }
    sseStatus.value = 'closed';
  }
}

function parseSseChunk(chunk: string): PaperSseEvent | null {
  const lines = chunk.split('\n');
  let data = '';
  for (const line of lines) {
    if (line.startsWith('data:')) {
      data += line.slice(5).trim();
    }
  }
  if (!data) return null;
  try {
    return JSON.parse(data) as PaperSseEvent;
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
  await stopPaperTask(currentTaskId.value);
  await refreshTask();
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
  const base = labels[artifact.type] || artifact.type;
  return artifact.version && artifact.version > 1 ? `${base} v${artifact.version}` : base;
}

function formatFileSize(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(2)} MB`;
}

function statusTagType(status: string) {
  if (status === 'COMPLETED') return 'success';
  if (status === 'FAILED' || status === 'STOPPED') return 'error';
  if (status === 'RUNNING' || status === 'PENDING') return 'info';
  if (status === 'PAUSED' || status === 'WAITING_INPUT') return 'warning';
  return 'default';
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

function canAcceptSuggestion(suggestion: PaperSuggestionResponse) {
  return suggestion.honestyGrade === 'A' && suggestion.evidenceCount > 0 && suggestion.applicable;
}

async function handleSuggestionChecked(suggestion: PaperSuggestionResponse, checked: boolean) {
  await updateSuggestionStatus(suggestion, checked ? 'ACCEPTED' : 'PROPOSED');
}

async function updateSuggestionStatus(suggestion: PaperSuggestionResponse, status: string) {
  if (!currentTaskId.value) return;
  suggestionSubmitting.value = suggestion.id;
  try {
    await updatePaperSuggestionStatus(currentTaskId.value, suggestion.id, status);
    await loadClarificationsAndSections(currentTaskId.value);
    ui.message.success(status === 'ACCEPTED' ? '已采纳建议' : status === 'REJECTED' ? '已拒绝建议' : '建议状态已更新');
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || '更新建议状态失败');
  } finally {
    suggestionSubmitting.value = null;
  }
}

function prettyJson(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
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

function hasProgressMeta(event: PaperSseEvent) {
  return event.progressPercent != null || event.currentSection != null || event.attempt != null || Boolean(event.stage);
}

function normalizeStage(stage: string | null) {
  if (!stage) return null;
  return legacyStageAlias[stage] || stage;
}

function stageLabel(stage: string | null) {
  const normalized = normalizeStage(stage);
  const labels: Record<string, string> = {
    PARSE: '解析 LaTeX',
    STRUCTURE_CHECK: '结构确认',
    RESEARCH_PROFILE: '研究画像',
    RETRIEVE: '文献检索',
    GAP_ANALYSIS: 'Gap 分析',
    POLISH: '分章润色',
    ASSEMBLE: '三件套组装',
    COMPLETE: '完成',
    STOPPED: '已停止',
    FAILED: '失败',
    PENDING: '等待中',
    RUNNING: '运行中',
  };
  return normalized ? labels[normalized] || normalized : '-';
}

function eventDisplayType(type: string) {
  const labels: Record<string, string> = {
    log: '日志',
    summary_ready: '摘要完成',
    sections: '章节开始',
    outer_round: '外层轮次',
    section_loop_start: '章节开始',
    section_attempt: '章节尝试',
    section_polished: '章节润色完成',
    section_review_done: '章节审查完成',
    paper_review_done: '跨章审查完成',
    review: '审查/摘要',
    references_ready: '文献结果',
    clarification_needed: '需要结构确认',
    clarification_resolved: '结构确认完成',
    paused: '已暂停',
    complete: '完成',
    error: '异常',
  };
  return labels[type] || type;
}

function eventProgressHint(event: PaperSseEvent) {
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
  const activeIndex = stageSteps.findIndex((step) => step.stage === normalizedCurrentStage.value);
  const index = stageSteps.findIndex((step) => step.stage === stage);
  return {
    'paper-stage-chip--done': activeIndex >= 0 && index < activeIndex,
    'paper-stage-chip--active': activeIndex >= 0 && index === activeIndex,
  };
}

function clampProgress(value: number) {
  return Math.max(0, Math.min(100, Math.round(value)));
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}
</script>
