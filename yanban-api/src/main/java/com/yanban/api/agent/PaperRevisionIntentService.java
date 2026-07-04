package com.yanban.api.agent;

import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PaperRevisionIntentService {

    private static final List<String> KEYWORDS = List.of(
            "润色论文", "修改论文", "帮我润色", "帮我修改", "论文修改", "论文润色",
            "polish paper", "revise paper", "paper revision", "revise my paper"
    );

    public PaperRevisionSuggestion suggest(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.trim().toLowerCase(Locale.ROOT);
        boolean matched = KEYWORDS.stream().anyMatch(normalized::contains)
                || (normalized.contains("论文") && (normalized.contains("润色") || normalized.contains("修改")));
        if (!matched) {
            return null;
        }
        return new PaperRevisionSuggestion(
                "/paper",
                "如果你想进行论文润色，请打开论文修改页继续上传 docx：/paper\n支持设置目标语言、轮次、阈值，并在页面中查看进度与下载结果。"
        );
    }

    public record PaperRevisionSuggestion(String url, String assistantMessage) {
    }
}
