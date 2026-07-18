import MarkdownIt from 'markdown-it';
import { describe, expect, it } from 'vitest';
import { configureMarkdownLinkPolicy } from '../src/utils/markdownLinkPolicy';

describe('Markdown link policy', () => {
  it('does not turn Project-relative filenames into external links', () => {
    const markdown = configureMarkdownLinkPolicy(new MarkdownIt({ linkify: true }));

    const html = markdown.renderInline('implementation.py paper_injection.tex should_never_be_read.txt');

    expect(html).not.toContain('<a ');
    expect(html).toContain('implementation.py');
  });

  it('keeps explicit links available', () => {
    const markdown = configureMarkdownLinkPolicy(new MarkdownIt({ linkify: true }));

    expect(markdown.renderInline('https://example.com')).toContain('href="https://example.com"');
    expect(markdown.renderInline('[source](https://example.com)')).toContain('href="https://example.com"');
  });
});
