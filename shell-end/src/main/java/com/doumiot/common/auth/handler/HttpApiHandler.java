package com.doumiot.common.auth.handler;

import com.doumiot.spring.component.GetBeanHelper;
import com.doumiot.spring.privilege.PrivilegesListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 扫描需要录入数据库的接口
 * <p>
 * 该处理器负责扫描系统中的HTTP接口，并将其注册到权限系统中，
 * 以便进行统一的权限管理和控制。
 */
@Slf4j
@Component
public class HttpApiHandler extends PrivilegesListener {

    public HttpApiHandler(GetBeanHelper getBeanHelper) {
        super(getBeanHelper);
    }

    /**
     * 在处理完模块权限后的回调方法
     *
     * @param moduleName 模块名称
     */
    @Override
    protected void afterHandle(String moduleName) {
        //将接口信息映射表异步插入数据库
    }

    /**
     * 在处理模块权限前的预处理方法
     *
     * @param moduleName 模块名称
     */
    @Override
    protected void preHandle(String moduleName) {
        log.info("开始处理模块api权限数据：{}", moduleName);
    }


}