package com.example.redisadmin.web;

import com.example.redisadmin.exception.BizException;
import com.example.redisadmin.exception.ErrorCode;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * 解析 {@code /api/c/{id}} 路径段为 {@code Long}。
 * 缺失或非数字 → 40001（带明确 msg）。
 * <p>未来若引入鉴权，本解析器所在的拦截链是统一注入点（加一个
 * {@code HandlerInterceptor} 即可全端点覆盖），无需改 Controller（见 §11.6）。</p>
 */
@Component
public class ProfileArgumentResolver implements HandlerMethodArgumentResolver {

    /**
     * 路径变量名，与 {@code /api/c/{id}} 中的段名一致。
     */
    private static final String PATH_VARIABLE = "id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(ProfileId.class)
                && (Long.class.equals(parameter.getParameterType()) || long.class.equals(parameter.getParameterType()));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String raw = pathVariable(webRequest, PATH_VARIABLE);
        if (raw == null || raw.isBlank()) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "路径缺少连接配置 id");
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw BizException.of(ErrorCode.VALIDATION_ERROR, "连接配置 id 必须为数字: " + raw);
        }
    }

    /**
     * 从请求属性中提取路径变量（Spring MVC 由 {@code RequestMappingHandlerMapping}
     * 解析 URI 模板后放入 {@link HandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE}）。
     */
    @SuppressWarnings("unchecked")
    private static String pathVariable(NativeWebRequest webRequest, String name) {
        Object attr = webRequest.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST);
        if (attr instanceof Map<?, ?> vars) {
            Object value = vars.get(name);
            return value != null ? value.toString() : null;
        }
        return null;
    }
}
