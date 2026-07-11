<template>
  <div class="message-markdown" v-html="renderedHtml" />
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify';
import MarkdownIt from 'markdown-it';
import { computed } from 'vue';

const props = defineProps<{
  content: string;
}>();

const markdown = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
});

const defaultLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options));

markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  if (token.attrIndex('href') >= 0) {
    token.attrSet('target', '_blank');
    token.attrSet('rel', 'noopener noreferrer');
  }
  return defaultLinkOpen(tokens, idx, options, env, self);
};

function normalizeLooseMarkdown(content: string) {
  return (content || '')
    .replace(/\r\n/g, '\n')
    // Some models emit "summary### 1 Title" without a line break; Markdown requires headings at line start.
    .replace(/([^\n])([ \t]*)(#{2,6})(?=[ \t]*(?:\d|[\u4e00-\u9fa5A-Za-z]))/g, '$1\n\n$3')
    // Also tolerate "###Title" once it has been moved to its own line.
    .replace(/^(#{2,6})(?=\S)/gm, '$1 ');
}

const renderedHtml = computed(() => DOMPurify.sanitize(markdown.render(normalizeLooseMarkdown(props.content || ''))));
</script>
