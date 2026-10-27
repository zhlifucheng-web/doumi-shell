package com.doumiot.sshconsole.controller;

import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.spring.result.Result;
import com.doumiot.sshconsole.service.SshFileFilterService;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.constraints.NotBlank;
import java.io.IOException;

@SshConsoleEnabled
@Validated
@RestController
@RequestMapping("/api/ssh-console/sftp/filter")
public class SshFileFilterController {
    private final SshFileFilterService filters;
    public SshFileFilterController(SshFileFilterService filters) { this.filters = filters; }

    @PostMapping("/start")
    public SshFileFilterService.Started start(@RequestBody @Validated StartRequest body) throws IOException {
        return filters.start(body.getSessionToken(), body.getPath(), body.getKeyword(), body.getEncoding(),
                body.isCaseSensitive(), body.getContextLines());
    }
    @PostMapping("/next")
    public SshFileFilterService.Batch next(@RequestBody @Validated CursorRequest body) throws IOException {
        return filters.next(body.getSessionToken(), body.getScanId());
    }
    @PostMapping("/cancel")
    public void cancel(@RequestBody @Validated CursorRequest body) throws IOException {
        filters.cancel(body.getSessionToken(), body.getScanId());
    }
    @ExceptionHandler(IOException.class)
    public Result filterFailure(IOException error) {
        return Result.failure(BusinessRuntimeException.ParamExceptionEnum.INNER_PARAM_IS_INVALID, error.getMessage());
    }
    @Data public static class StartRequest {
        @NotBlank private String sessionToken;
        @NotBlank private String path;
        @NotBlank private String keyword;
        private String encoding = "UTF-8";
        private boolean caseSensitive;
        private int contextLines;
    }
    @Data public static class CursorRequest {
        @NotBlank private String sessionToken;
        @NotBlank private String scanId;
    }
}
