export interface MarkdownNormalizationOptions {
  demoteSpacedProseHeadings?: boolean;
}

export function normalizeLooseMarkdown(content: string, options?: MarkdownNormalizationOptions): string;
