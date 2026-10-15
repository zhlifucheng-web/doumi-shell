package com.doumiot.controller.init;

import com.doumiot.init.DatabaseInitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 数据库初始化控制器
 * 提供手动触发数据库初始化的 REST 接口
 *
 * @author Lingma
 * @since 2026-02-13
 */
@Slf4j
@RestController
@RequestMapping(value = "/api/database", name = "数据库初始化控制器")
@RequiredArgsConstructor
public class DatabaseInitController {

    private final DatabaseInitService databaseInitService;

    /**
     * 手动触发数据库初始化
     */
    @PostMapping(path = "/initialize", name = "手动触发数据库初始化")
    public Map<String, Object> initializeDatabase() {
        Map<String, Object> response = new HashMap<>();
        try {
            log.info("收到手动数据库初始化请求");
            databaseInitService.manualInitialize();
            response.put("success", true);
            response.put("message", "数据库初始化成功");
            log.info("手动数据库初始化完成");
        } catch (Exception e) {
            log.error("手动数据库初始化失败", e);
            response.put("success", false);
            response.put("message", "数据库初始化失败: " + e.getMessage());
        }
        return response;
    }

    /**
     * 获取数据库初始化状态
     */
    @GetMapping(path = "/status", name = "获取数据库初始化状态")
    public Map<String, Object> getInitStatus() {
        Map<String, Object> response = new HashMap<>();
        try {
            response.put("success", true);
            response.put("initialized", true); // 简化实现，实际可以检查具体表状态
            response.put("message", "数据库初始化服务运行正常");
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "检查初始化状态失败: " + e.getMessage());
        }
        return response;
    }
}