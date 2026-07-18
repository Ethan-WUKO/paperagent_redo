import type MarkdownIt from 'markdown-it';

export function configureMarkdownLinkPolicy(markdown: MarkdownIt) {
  // Project-relative filenames such as implementation.py are data, not fuzzy domains.
  // Explicit schemes and explicit Markdown links remain linkable.
  markdown.linkify.set({ fuzzyLink: false });
  return markdown;
}
