package com.yanban.api.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class ProjectUploadExceptionHandlerTest {

    @Test
    void oversizedMultipartRequestReturnsStablePayloadTooLargeResponse() {
        var response = new ProjectExceptionHandler().uploadTooLarge(
                new MaxUploadSizeExceededException(128L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isEqualTo(new ProjectErrorResponse(
                "PROJECT_UPLOAD_TOO_LARGE",
                "The selected Project folder is too large. Remove generated or binary files and try again."));
    }
}
