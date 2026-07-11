import assert from 'node:assert/strict';
import test from 'node:test';
import MarkdownIt from 'markdown-it';
import { normalizeLooseMarkdown } from '../src/utils/markdownNormalization.mjs';

const markdown = new MarkdownIt({ breaks: true });

const assistant440Shape = [
  '## `t_opt` 文件是什么？',
  '',
  '这是一个优化结果输出文件，内容为中文+数据格式。具体来说：',
  '',
  '-文件包含了两组权重参数下的优化结果。',
  '-每组结果是一个 8×2 的复数张量。',
  '-这很可能是极化优化算法运行后自动输出的日志/结果文件。',
  '',
  '##它的作用这是在雷达抗干扰/极化优化研究中，通过优化算法求解出的最优发射极化矢量。权重（weight）值可能代表某种约束强度，不同权重下优化出的极化状态有所不同。',
  '',
  '##关于删除很抱歉，我无法帮您删除这个文件。我当前在项目的只读模式下运行，拥有的工具都不具备删除或修改文件的能力。',
  '',
  '```python',
  '##代码中的井号不能成为标题。',
  '-内容不能成为列表',
  'print("summary### 1 代码里的标题")',
  '```',
].join('\n');

test('repairs the real assistant response shape without promoting prose', () => {
  const normalized = normalizeLooseMarkdown(assistant440Shape);
  assert.match(normalized, /^## `t_opt` 文件是什么？/);
  assert.match(normalized, /- 文件包含了两组权重参数/);
  assert.match(normalized, /- 每组结果是一个/);
  assert.match(normalized, /\n它的作用这是在雷达抗干扰/);
  assert.match(normalized, /\n关于删除很抱歉/);
  assert.doesNotMatch(normalized, /## 它的作用这是/);
  assert.doesNotMatch(normalized, /## 关于删除很抱歉/);

  const html = markdown.render(normalized);
  assert.equal((html.match(/<h2>/g) || []).length, 1);
  assert.match(html, /<h2><code>t_opt<\/code> 文件是什么？<\/h2>/);
  assert.doesNotMatch(html, /<h2>[^<]*(?:它的作用|关于删除)/);
  assert.equal((html.match(/<li>/g) || []).length, 3);
});

test('does not normalize anything inside backtick or tilde fences', () => {
  const fenced = [
    '```python',
    '##代码中的井号不能成为标题。',
    '-内容不能成为列表',
    '```',
    '~~~text',
    '##另一个围栏',
    '-另一个列表',
    '~~~~',
  ].join('\n');
  assert.equal(normalizeLooseMarkdown(fenced), fenced);
});

test('repairs only conservative heading and list cases', () => {
  const source = [
    '##短标题',
    '总结如下### 1 目录结构',
    '-内容',
    '-10',
    '-.5',
    '-rf',
    '--force',
    '---',
    '    -缩进代码',
  ].join('\n');
  assert.equal(normalizeLooseMarkdown(source), [
    '## 短标题',
    '总结如下',
    '',
    '### 1 目录结构',
    '- 内容',
    '-10',
    '-.5',
    '-rf',
    '--force',
    '---',
    '    -缩进代码',
  ].join('\n'));
});

test('does not split embedded heading markers inside inline code', () => {
  const source = '请保留 `value### 1 中文` 这一段。';
  assert.equal(normalizeLooseMarkdown(source), source);
});

test('demotes sentence-sized prose even when the heading marker already has a space', () => {
  const shortHeading = '## 是否可以删除？';
  const proseHeading = '## 关于删除很抱歉，我当前只能在项目的只读模式下运行，拥有的工具不具备删除或修改文件的能力。您可以在确认文件不再需要后自行处理。';
  assert.equal(normalizeLooseMarkdown(shortHeading), shortHeading);
  assert.equal(normalizeLooseMarkdown(proseHeading), proseHeading);
  assert.equal(normalizeLooseMarkdown(proseHeading, { demoteSpacedProseHeadings: true }), proseHeading.slice(3));
});

test('leaves four-space and tab-indented code unchanged', () => {
  const source = [
    '    print("summary### 1 Embedded")',
    '\t##缩进代码不是标题',
    '    -内容不是列表',
  ].join('\n');
  assert.equal(normalizeLooseMarkdown(source), source);
});
