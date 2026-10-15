package com.doumiot.spring.privilege;

import com.doumiot.spring.component.GetBeanHelper;
import com.doumiot.spring.exception.BusinessRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 权限监听器：启动时扫描带 {@link Privilege} 的 Handler，登记 API 元数据。
 * <p>
 * 路径与 HTTP 方法优先取自 {@link RequestMappingInfo}（与 Spring MVC 实际注册一致），
 * 仅在无法解析时再回退到注解拼接逻辑。
 */
@Slf4j
public abstract class PrivilegesListener implements ApplicationRunner {

    private static final String AUTH_ANY = "ANY";
    private static final String AUTH_ALL = "ALL";

    @Value("${spring.application.name}")
    private String moduleName;

    private final GetBeanHelper getBeanHelper;

    public PrivilegesListener(GetBeanHelper getBeanHelper) {
        this.getBeanHelper = getBeanHelper;
    }

    @Override
    public void run(ApplicationArguments args) {
        preHandle(moduleName);
        log.info("扫描需要鉴权的Api-------------------------------------------------------------------------------");
        RequestMappingHandlerMapping mapping = getBeanHelper.getBean(RequestMappingHandlerMapping.class);
        Map<RequestMappingInfo, HandlerMethod> methodMap = mapping.getHandlerMethods();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : methodMap.entrySet()) {
            HandlerMethod handlerMethod = entry.getValue();
            Privilege classPrivilege = handlerMethod.getBeanType().getAnnotation(Privilege.class);
            if (classPrivilege == null) {
                log.debug("类没有注解---{}", handlerMethod.getBeanType().getName());
                continue;
            }
            Privilege methodPrivilege = handlerMethod.getMethodAnnotation(Privilege.class);
            if (methodPrivilege == null) {
                log.debug("方法没有注解---{}", handlerMethod.getMethod().getName());
                continue;
            }
            RequestMapping classRequest = handlerMethod.getBeanType().getAnnotation(RequestMapping.class);
            if (classRequest == null) {
                throw new BusinessRuntimeException(BusinessRuntimeException.ServerExceptionEnum.API_ERROR,
                        "如果使用Privilege注解，类必须添加RequestMapping注解");
            }

            String classDisplayName = resolveClassDisplayName(classRequest, handlerMethod);
            String methodDisplayName = resolveMethodDisplayName(handlerMethod.getMethod());
            RequestMappingInfo info = entry.getKey();

            Set<String> patterns = resolvePatterns(info, handlerMethod, classRequest);
            Set<RequestMethod> methods = resolveHttpMethods(info, handlerMethod.getMethod());
            String authMode = resolveAuthMode(classPrivilege, methodPrivilege);

            for (RequestMethod type : methods) {
                for (String pattern : patterns) {
                    handlePrivilege(moduleName, classDisplayName, methodDisplayName, "", pattern, type,
                            methodPrivilege.isPublic(), authMode);
                }
            }
        }
        afterHandle(moduleName);
    }

    private static String resolveClassDisplayName(RequestMapping classRequest, HandlerMethod handlerMethod) {
        if (classRequest.name() != null && !classRequest.name().isEmpty()) {
            return classRequest.name();
        }
        return handlerMethod.getBeanType().getSimpleName();
    }

    private static String resolveMethodDisplayName(Method method) {
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping mr = method.getAnnotation(RequestMapping.class);
            if (mr.name() != null && !mr.name().isEmpty()) {
                return mr.name();
            }
        } else if (method.isAnnotationPresent(GetMapping.class)) {
            String n = method.getAnnotation(GetMapping.class).name();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            String n = method.getAnnotation(PostMapping.class).name();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            String n = method.getAnnotation(PutMapping.class).name();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            String n = method.getAnnotation(PatchMapping.class).name();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            String n = method.getAnnotation(DeleteMapping.class).name();
            if (n != null && !n.isEmpty()) {
                return n;
            }
        }
        return method.getName();
    }

    /**
     * 与 Spring 注册的 path 模式一致；若为空则回退到类+方法注解拼接。
     */
    private static Set<String> resolvePatterns(RequestMappingInfo info, HandlerMethod handlerMethod,
                                               RequestMapping classRequest) {
        if (info.getPatternsCondition() != null) {
            Set<String> registered = info.getPatternsCondition().getPatterns();
            if (registered != null && !registered.isEmpty()) {
                return registered;
            }
        }
        return patternsFromAnnotations(handlerMethod.getMethod(), classRequest);
    }

    private static Set<String> patternsFromAnnotations(Method method, RequestMapping classRequest) {
        String[] classPaths = classRequest.path();
        if (classPaths.length == 0) {
            classPaths = new String[]{""};
        }
        String[] methodPaths;
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping methodRequest = method.getAnnotation(RequestMapping.class);
            methodPaths = methodRequest.path();
        } else {
            methodPaths = getRequestMethodPaths(method);
        }
        if (methodPaths.length == 0) {
            methodPaths = new String[]{""};
        }
        Set<String> out = new LinkedHashSet<>();
        for (String classPath : classPaths) {
            for (String methodPath : methodPaths) {
                out.add(combinePath(classPath, methodPath));
            }
        }
        return out;
    }

    private static String combinePath(String classPath, String methodPath) {
        String c = classPath == null ? "" : classPath;
        String m = methodPath == null ? "" : methodPath;
        if (m.isEmpty()) {
            return c.isEmpty() ? "/" : c;
        }
        if (c.isEmpty()) {
            return m.startsWith("/") ? m : "/" + m;
        }
        if (c.endsWith("/")) {
            return m.startsWith("/") ? c + m.substring(1) : c + m;
        }
        return m.startsWith("/") ? c + m : c + "/" + m;
    }

    private static Set<RequestMethod> resolveHttpMethods(RequestMappingInfo info, Method method) {
        if (info.getMethodsCondition() != null) {
            Set<RequestMethod> registered = info.getMethodsCondition().getMethods();
            if (registered != null && !registered.isEmpty()) {
                return registered;
            }
        }
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMethod[] types = method.getAnnotation(RequestMapping.class).method();
            if (types.length > 0) {
                return new LinkedHashSet<>(Arrays.asList(types));
            }
            // 与 Spring 一致：@RequestMapping 未指定 method 时允许任意方法，登记常用动词避免漏接口
            return new LinkedHashSet<>(Arrays.asList(
                    RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.DELETE, RequestMethod.PATCH));
        }
        RequestMethod[] inferred = getRequestMethodTypes(method);
        return new LinkedHashSet<>(Arrays.asList(inferred));
    }

    private static String resolveAuthMode(Privilege classPrivilege, Privilege methodPrivilege) {
        String fromMethod = normalizeAuthMode(methodPrivilege.authMode());
        if (fromMethod != null) {
            return fromMethod;
        }
        String fromClass = normalizeAuthMode(classPrivilege.authMode());
        if (fromClass != null) {
            return fromClass;
        }
        return AUTH_ANY;
    }

    private static String normalizeAuthMode(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (AUTH_ALL.equals(u)) {
            return AUTH_ALL;
        }
        if (AUTH_ANY.equals(u)) {
            return AUTH_ANY;
        }
        throw new BusinessRuntimeException(BusinessRuntimeException.ServerExceptionEnum.API_ERROR,
                "@Privilege.authMode 仅支持 ANY 或 ALL，实际为: " + raw);
    }

    private static RequestMethod[] getRequestMethodTypes(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return new RequestMethod[]{RequestMethod.GET};
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            return new RequestMethod[]{RequestMethod.POST};
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            return new RequestMethod[]{RequestMethod.PUT};
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            return new RequestMethod[]{RequestMethod.PATCH};
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            return new RequestMethod[]{RequestMethod.DELETE};
        }
        return new RequestMethod[]{RequestMethod.GET};
    }

    private static String[] getRequestMethodPaths(Method method) {
        if (method.isAnnotationPresent(GetMapping.class)) {
            return method.getAnnotation(GetMapping.class).path();
        } else if (method.isAnnotationPresent(PostMapping.class)) {
            return method.getAnnotation(PostMapping.class).path();
        } else if (method.isAnnotationPresent(PutMapping.class)) {
            return method.getAnnotation(PutMapping.class).path();
        } else if (method.isAnnotationPresent(PatchMapping.class)) {
            return method.getAnnotation(PatchMapping.class).path();
        } else if (method.isAnnotationPresent(DeleteMapping.class)) {
            return method.getAnnotation(DeleteMapping.class).path();
        }
        return new String[0];
    }

    protected abstract void afterHandle(String moduleName);

    /**
     * @param classPath  历史参数；基于 {@link RequestMappingInfo} 扫描时通常为空串，完整路径在 methodPath
     * @param methodPath 相对应用的 path 模式（一般带前导 /，如 /auth/login 或 /permission/{permId}）
     * @param authMode   ANY 或 ALL
     */
    protected void handlePrivilege(String moduleName, String className, String methodName,
                                   String classPath, String methodPath, RequestMethod method,
                                   boolean isPublic, String authMode) {
    }

    protected abstract void preHandle(String moduleName);
}
