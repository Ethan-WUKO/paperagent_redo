package com.yanban.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FileProcessingConsumer {

    private final ObjectMapper objectMapper;
    private final FileProcessingService fileProcessingService;

    public FileProcessingConsumer(ObjectMapper objectMapper, FileProcessingService fileProcessingService) {
        this.objectMapper = objectMapper;
        this.fileProcessingService = fileProcessingService;
    }

    @KafkaListener(topics = "${yanban.knowledge.upload.processing-topic:file-processing}", groupId = "yanban-kb-processing")
    public void onMessage(String payload) {
        try {
            FileProcessingMessage message = objectMapper.readValue(payload, FileProcessingMessage.class);
            fileProcessingService.process(message);
        } catch (Exception ex) {
            throw new IllegalStateException("处理知识库文件消息失败", ex);
        }
    }
}
