const LOOSE_HEADING_MAX_LENGTH = 48;
const PROSE_PUNCTUATION_MIN_LENGTH = 24;
const FENCE_PATTERN = /^ {0,3}(`{3,}|~{3,})(.*)$/;

function isFenceClose(line, fence) {
  const match = line.match(/^ {0,3}(`+|~+)[ \t]*$/);
  return Boolean(match && match[1][0] === fence.marker && match[1].length >= fence.length);
}

function looksLikeProse(content) {
  const visibleContent = content.replace(/[`*_~\[\]()]/g, '').trim();
  const visibleLength = [...visibleContent].length;
  return visibleLength > LOOSE_HEADING_MAX_LENGTH
    || (visibleLength > PROSE_PUNCTUATION_MIN_LENGTH && /[，。！？；,.!?;]/.test(visibleContent));
}

function normalizeStandaloneLine(line, options) {
  // A four-space indent is Markdown code, so only repair list markers with up to three spaces.
  const looseListItem = line.match(/^( {0,3})-(\S.*)$/);
  if (looseListItem) {
    const body = looseListItem[2];
    const likelyListContent = /^[\u3400-\u9fff]|^(?:\*\*|__|`|\[)/.test(body);
    const negativeNumber = /^(?:\d|\.\d)/.test(body);
    const commandOption = /^-|^[A-Za-z](?:[A-Za-z0-9_-]*)(?:=|$)/.test(body);
    const separator = /^[-*_]{2,}\s*$/.test(body);
    if (likelyListContent && !negativeNumber && !commandOption && !separator) {
      return `${looseListItem[1]}- ${body}`;
    }
  }

  const heading = line.match(/^( {0,3})(#{2,6})([ \t]*)(\S.*)$/);
  if (!heading) return line;

  const [, indentation, hashes, , content] = heading;
  // Keep compact labels as headings, but never leave sentence-sized model prose in a
  // large heading merely because it starts with ## (with or without a following space).
  const missingHeadingSpace = !heading[3];
  if (looksLikeProse(content) && (missingHeadingSpace || options.demoteSpacedProseHeadings)) {
    return `${indentation}${content}`;
  }
  if (heading[3]) return line;
  return `${indentation}${hashes} ${content}`;
}

function findEmbeddedHeading(line) {
  let inlineCodeDelimiter = 0;
  for (let index = 0; index < line.length; index += 1) {
    if (line[index] === '`') {
      let end = index + 1;
      while (line[end] === '`') end += 1;
      const runLength = end - index;
      inlineCodeDelimiter = inlineCodeDelimiter === runLength ? 0 : (inlineCodeDelimiter === 0 ? runLength : inlineCodeDelimiter);
      index = end - 1;
      continue;
    }
    if (inlineCodeDelimiter || line[index] !== '#' || index === 0 || line[index - 1] === '#') continue;

    let end = index;
    while (line[end] === '#') end += 1;
    const hashCount = end - index;
    if (hashCount < 2 || hashCount > 6) continue;
    while (line[end] === ' ' || line[end] === '\t') end += 1;
    if (/^[\d\u3400-\u9fffA-Za-z]$/.test(line[end] || '') && line.slice(0, index).trim()) return index;
  }
  return -1;
}

function normalizeOutsideFenceLine(line, options) {
  if (/^(?: {4}|\t)/.test(line)) return [line];
  const embeddedHeadingIndex = findEmbeddedHeading(line);
  if (embeddedHeadingIndex < 0) return [normalizeStandaloneLine(line, options)];

  const before = line.slice(0, embeddedHeadingIndex).trimEnd();
  const heading = normalizeStandaloneLine(line.slice(embeddedHeadingIndex), options);
  return [normalizeStandaloneLine(before, options), '', heading];
}

/**
 * Repairs a small set of common model-generated Markdown mistakes without changing fenced code.
 * Deliberately ambiguous English `-option` lines and sentence-sized `##prose` are not promoted.
 *
 * @param {string} content
 * @param {{ demoteSpacedProseHeadings?: boolean }} [options]
 * @returns {string}
 */
export function normalizeLooseMarkdown(content, options = {}) {
  const normalizedOptions = {
    demoteSpacedProseHeadings: Boolean(options.demoteSpacedProseHeadings),
  };
  const lines = (content || '').replace(/\r\n?/g, '\n').split('\n');
  const normalized = [];
  let fence = null;

  lines.forEach((line) => {
    if (fence) {
      normalized.push(line);
      if (isFenceClose(line, fence)) fence = null;
      return;
    }

    const openingFence = line.match(FENCE_PATTERN);
    if (openingFence) {
      fence = { marker: openingFence[1][0], length: openingFence[1].length };
      normalized.push(line);
      return;
    }

    normalized.push(...normalizeOutsideFenceLine(line, normalizedOptions));
  });

  return normalized.join('\n');
}
