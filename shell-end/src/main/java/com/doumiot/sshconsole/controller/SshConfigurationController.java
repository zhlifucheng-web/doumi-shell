package com.doumiot.sshconsole.controller;

import com.doumiot.common.exception.enums.ParamExceptionEnum;
import com.doumiot.spring.exception.BusinessRuntimeException;
import com.doumiot.sshconsole.service.SshConfigurationService;
import com.doumiot.sshconsole.config.SshConsoleEnabled;
import com.doumiot.config.ConfigurationArchive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;

@RestController
@SshConsoleEnabled
@RequestMapping("/api/ssh-console/configuration")
public class SshConfigurationController {
    @Resource private SshConfigurationService service;

    @PostMapping("/export")
    public ConfigurationArchive.Archive exportAll(@RequestBody TransferRequest request, HttpServletResponse response) throws Exception {
        response.setHeader("Cache-Control", "no-store");
        try { return service.exportAll(request.getPassword(), request.isEncrypted()); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    @PostMapping("/import")
    public Map<String, Integer> importAll(@RequestBody TransferRequest request) throws Exception {
        try { return service.importAll(request.getArchive(), request.getPassword()); }
        catch (IllegalArgumentException e) { throw invalid(e); }
    }

    private static BusinessRuntimeException invalid(IllegalArgumentException error) {
        return new BusinessRuntimeException(ParamExceptionEnum.IN_PARAM_EMPTY, error.getMessage());
    }

    @Getter @Setter
    public static class TransferRequest {
        private boolean encrypted = true;
        private String password;
        private ConfigurationArchive.Archive archive;
    }
}
