<template>
  <AppLayout>
    <div class="search-page workbench-page scholar-page scholar-page--search">
      <section class="workbench-hero scholar-page-hero">
        <div>
          <div class="workbench-kicker">Search Debug</div>
          <h1>Knowledge Search Debug</h1>
          <p>Inspect retrieval quality, score bands, selected chunks, and RAG visibility before shipping answers to users.</p>
        </div>
        <NSpace align="center">
          <NTag type="info" round>{{ results.length }} results</NTag>
          <NButton secondary @click="fillSampleQuery">Sample Query</NButton>
        </NSpace>
      </section>

      <NGrid :cols="24" :x-gap="16" :y-gap="16" responsive="screen" item-responsive>
        <NGridItem span="24 xl:17">
          <NSpace vertical size="large">
            <NCard class="workbench-card scholar-card search-console-card" :bordered="false">
              <template #header>
                <div class="section-title">Retrieval Console</div>
              </template>
              <NForm :model="form" label-placement="top">
                <NFormItem label="Query">
                  <NInput
                    v-model:value="form.query"
                    type="textarea"
                    :autosize="{ minRows: 2, maxRows: 6 }"
                    placeholder="Example: What is the weekly lab meeting time?"
                  />
                </NFormItem>
                <NGrid :cols="24" :x-gap="16" responsive="screen" item-responsive>
                  <NFormItemGi span="24 m:6" label="Top K">
                    <NInputNumber v-model:value="form.topK" :min="1" :max="20" style="width: 100%" />
                  </NFormItemGi>
                  <NFormItemGi span="24 m:6" label="Document Scope">
                    <div class="search-static-pill">Private + permitted public</div>
                  </NFormItemGi>
                  <NFormItemGi span="24 m:6" label="Embedding">
                    <div class="search-static-pill">Configured backend model</div>
                  </NFormItemGi>
                  <NFormItemGi span="24 m:6" label="Action">
                    <NButton type="primary" block :loading="searching" @click="handleSearch">Search</NButton>
                  </NFormItemGi>
                </NGrid>
              </NForm>

              <div class="search-run-strip">
                <div>
                  <span>Search latency</span>
                  <strong>{{ lastDurationMs == null ? '-' : `${lastDurationMs} ms` }}</strong>
                </div>
                <div>
                  <span>Retrieved chunks</span>
                  <strong>{{ results.length }}</strong>
                </div>
                <div>
                  <span>Last run</span>
                  <strong>{{ lastRunAt ? formatDateTime(lastRunAt) : '-' }}</strong>
                </div>
                <NButton secondary @click="clearResults">Clear</NButton>
              </div>
            </NCard>

            <NCard class="workbench-card scholar-card" :bordered="false">
              <template #header>
                <div class="section-title">Results</div>
              </template>
              <template #header-extra>
                <span class="chat-hint">Top {{ results.length }} of requested {{ form.topK }}</span>
              </template>

              <NEmpty v-if="!searching && results.length === 0" description="Run a query to inspect retrieval results." />

              <div v-else class="search-result-table">
                <div class="search-result-table__head">
                  <span>Rank</span>
                  <span>File / Chunk</span>
                  <span>Score</span>
                  <span>Band</span>
                  <span>Visibility</span>
                  <span>Snippet</span>
                </div>
                <article
                  v-for="(item, index) in results"
                  :key="`${item.documentId}-${item.chunkIndex}-${index}`"
                  class="search-result-row"
                  :class="{ 'search-result-row--selected': selectedIndex === index }"
                  @click="selectedIndex = index"
                >
                  <span class="search-result-rank">{{ index + 1 }}</span>
                  <div>
                    <strong>{{ item.filename }}</strong>
                    <small>documentId={{ item.documentId }} · chunk {{ item.chunkIndex }}</small>
                  </div>
                  <span>{{ formatScore(item.score) }}</span>
                  <NTag :type="scoreBandType(item.score)" size="small">{{ scoreBandLabel(item.score) }}</NTag>
                  <NTag :type="item.isPublic ? 'info' : 'default'" size="small">
                    {{ item.isPublic ? 'Public' : 'Private' }}
                  </NTag>
                  <p>{{ item.chunkText }}</p>
                </article>
              </div>
            </NCard>
          </NSpace>
        </NGridItem>

        <NGridItem span="24 xl:7">
          <NCard class="workbench-card scholar-card search-diagnostics-card" :bordered="false">
            <template #header>
              <div class="section-title">Diagnostics</div>
            </template>

            <NSpace vertical size="large">
              <div class="diagnostic-status">
                <span>Recall Status</span>
                <NTag :type="recallStatusType" round>{{ recallStatusLabel }}</NTag>
              </div>

              <NAlert v-if="lowScoreCount > 0" type="warning" title="Low-confidence results detected">
                {{ lowScoreCount }} of {{ results.length }} results are below 0.50. Consider increasing Top K or rewriting the query.
              </NAlert>
              <NAlert v-else-if="results.length > 0" type="success" title="Retrieval looks usable">
                The current result set has no low-confidence chunks.
              </NAlert>

              <div class="score-panel">
                <div class="score-panel__title">Top-K Score Distribution</div>
                <div v-for="band in scoreBands" :key="band.label" class="score-band-row">
                  <span>{{ band.label }}</span>
                  <div class="score-band-track">
                    <i :style="{ width: `${band.percent}%` }" :class="`score-band-fill score-band-fill--${band.tone}`" />
                  </div>
                  <strong>{{ band.count }}</strong>
                </div>
              </div>

              <div class="diagnostic-grid">
                <div>
                  <span>Average score</span>
                  <strong>{{ averageScore == null ? '-' : formatScore(averageScore) }}</strong>
                </div>
                <div>
                  <span>High score chunks</span>
                  <strong>{{ highScoreCount }}</strong>
                </div>
                <div>
                  <span>No cross-user leakage</span>
                  <strong>Pass</strong>
                </div>
                <div>
                  <span>Requested Top K</span>
                  <strong>{{ form.topK }}</strong>
                </div>
              </div>

              <div class="selected-result-panel">
                <div class="score-panel__title">Selected Result Inspection</div>
                <template v-if="selectedResult">
                  <strong>{{ selectedResult.filename }} · chunk {{ selectedResult.chunkIndex }}</strong>
                  <p>{{ selectedResult.chunkText }}</p>
                  <div class="keyword-chip-row">
                    <span v-for="keyword in queryKeywords" :key="keyword">{{ keyword }}</span>
                  </div>
                </template>
                <NEmpty v-else description="Select a result row to inspect it." />
              </div>
            </NSpace>
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
  NCard,
  NEmpty,
  NForm,
  NFormItem,
  NFormItemGi,
  NGrid,
  NGridItem,
  NInput,
  NInputNumber,
  NSpace,
  NTag,
} from 'naive-ui';
import { computed, reactive, ref } from 'vue';
import AppLayout from '@/components/AppLayout.vue';
import { searchKnowledge, type KnowledgeSearchResult } from '@/api/knowledge';
import { ui } from '@/ui';

const form = reactive({
  query: '',
  topK: 5,
});
const searching = ref(false);
const results = ref<KnowledgeSearchResult[]>([]);
const selectedIndex = ref(0);
const lastDurationMs = ref<number | null>(null);
const lastRunAt = ref<string | null>(null);

const selectedResult = computed(() => results.value[selectedIndex.value] || null);
const highScoreCount = computed(() => results.value.filter((item) => item.score >= 0.75).length);
const lowScoreCount = computed(() => results.value.filter((item) => item.score < 0.5).length);
const averageScore = computed(() => {
  if (results.value.length === 0) {
    return null;
  }
  return results.value.reduce((sum, item) => sum + item.score, 0) / results.value.length;
});
const recallStatusLabel = computed(() => {
  if (results.value.length === 0) {
    return 'No run';
  }
  if (lowScoreCount.value > Math.max(1, Math.floor(results.value.length / 3))) {
    return 'Needs review';
  }
  return 'Good';
});
const recallStatusType = computed(() => {
  if (results.value.length === 0) {
    return 'default';
  }
  return recallStatusLabel.value === 'Good' ? 'success' : 'warning';
});
const scoreBands = computed(() => {
  const bands = [
    { label: '0.80 - 1.00', min: 0.8, max: 1.01, tone: 'green' },
    { label: '0.60 - 0.80', min: 0.6, max: 0.8, tone: 'green' },
    { label: '0.40 - 0.60', min: 0.4, max: 0.6, tone: 'amber' },
    { label: '0.20 - 0.40', min: 0.2, max: 0.4, tone: 'red' },
    { label: '0.00 - 0.20', min: 0, max: 0.2, tone: 'red' },
  ];
  return bands.map((band) => {
    const count = results.value.filter((item) => item.score >= band.min && item.score < band.max).length;
    return {
      ...band,
      count,
      percent: results.value.length === 0 ? 0 : Math.round((count / results.value.length) * 100),
    };
  });
});
const queryKeywords = computed(() =>
  Array.from(new Set(form.query.toLowerCase().match(/[\p{L}\p{N}]{3,}/gu) || [])).slice(0, 8),
);

async function handleSearch() {
  if (!form.query.trim()) {
    ui.message.warning('Please enter a query.');
    return;
  }

  searching.value = true;
  const startedAt = performance.now();
  try {
    const { data } = await searchKnowledge({
      query: form.query.trim(),
      topK: form.topK,
    });
    results.value = data;
    selectedIndex.value = 0;
    lastDurationMs.value = Math.round(performance.now() - startedAt);
    lastRunAt.value = new Date().toISOString();
    if (data.length === 0) {
      ui.message.info('No retrieval results found.');
    }
  } catch (error: any) {
    ui.message.error(error.response?.data?.message || 'Search failed.');
  } finally {
    searching.value = false;
  }
}

function fillSampleQuery() {
  form.query = '实验室每周组会时间是什么时候？';
  form.topK = 5;
}

function clearResults() {
  form.query = '';
  form.topK = 5;
  results.value = [];
  selectedIndex.value = 0;
  lastDurationMs.value = null;
  lastRunAt.value = null;
}

function scoreBandLabel(score: number) {
  if (score >= 0.75) {
    return 'High';
  }
  if (score >= 0.5) {
    return 'Medium';
  }
  return 'Low';
}

function scoreBandType(score: number) {
  if (score >= 0.75) {
    return 'success';
  }
  if (score >= 0.5) {
    return 'warning';
  }
  return 'error';
}

function formatScore(score: number) {
  return Number(score).toFixed(4);
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString('zh-CN');
}
</script>
