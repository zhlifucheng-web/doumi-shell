package com.doumiot.common.aspect;

import com.doumiot.common.objects.dto.BaseRequestDTO;
import com.doumiot.common.util.LogUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;

/**
 * 支持所有参数打印的切面，Controller方法参数打印到日志中
 */
@Component
@Aspect
@Order(Integer.MAX_VALUE) // 设置为最低优先级
public class ParamsPrintAspect {

    private static final Logger logger = LoggerFactory.getLogger(ParamsPrintAspect.class);

    /**
     * 环绕通知，打印方法参数和返回值
     *
     * @param joinPoint 切点
     * @return 方法执行结果
     * @throws Throwable 异常
     */
    @Around("com.doumiot.common.aspect.ControllerPointcuts.requestMappingAnnotatedMethods()")
    public Object doAround(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = null;
        if (attributes != null) {
            request = attributes.getRequest();
        }

        String method = "";
        String uri = "";
        String remoteAddr = "";
        if (request != null) {
            method = request.getMethod();
            uri = request.getRequestURI();
            remoteAddr = request.getRemoteAddr();
        }

        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        Object[] args = joinPoint.getArgs();
        // SSH 请求含账户凭据、脚本和备份密码，只记录接口元信息。
        boolean sensitive = uri.startsWith("/api/ssh-console");

        // 尝试获取BaseRequestDTO参数以用于日志打印
        BaseRequestDTO baseRequestDTO = null;
        for (Object arg : args) {
            if (arg instanceof BaseRequestDTO) {
                baseRequestDTO = (BaseRequestDTO) arg;
                break;
            }
        }

        // 设置MDC上下文信息
        LogUtil.setMDCContext(baseRequestDTO);

        // 使用增强的日志格式打印请求参数
        logger.info("====== Request Start ====== Class: {}, Method: {}, Args: {}", className, methodName,
                sensitive ? "[redacted]" : Arrays.toString(args));

        Object result;
        try {
            result = joinPoint.proceed();
            long endTime = System.currentTimeMillis();
            // 使用增强的日志格式打印返回结果
            logger.info("");
            logger.info("====== Response Start ====== Class: {}, Method: {}, Cost: {}ms, Return: {}", className, methodName, (endTime - startTime), sensitive ? "[redacted]" : result);
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            logger.error("Class: {}, Method: {}, Cost: {}ms", className, methodName, (endTime - startTime), e);
            throw e;
        } finally {
            // 清除MDC上下文信息
            LogUtil.clearMDCContext();
        }

        return result;
    }
}
