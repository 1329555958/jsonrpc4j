package com.googlecode.jsonrpc4j.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.jsonrpc4j.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static java.lang.String.format;
import static org.springframework.util.ClassUtils.forName;
import static org.springframework.util.ClassUtils.getAllInterfacesForClass;

/**
 * <p>This exporter class is deprecated because it exposes all beans from a spring context that has the
 * {@link JsonRpcService} annotation.  If that context is also consuming JSON-RPC services from a remote
 * system and has proxy clients instantiated in the same context then those proxy clients will also
 * be (inadvertently) exposed by {@link AutoJsonRpcServiceExporter}.  To avoid this, switch over to use
 * {@link AutoJsonRpcServiceImplExporter} which exposes specific implementations of the JSON-RPC services'
 * interfaces rather than all beans that implement {@link JsonRpcService}.</p>
 * <p>
 * 通过配置扫描包路径，避免暴露不该暴露的rpc接口
 * <p>
 */
//@Deprecated
@SuppressWarnings("unused")
public class AutoJsonRpcServiceExporter implements BeanFactoryPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AutoJsonRpcServiceExporter.class);


    private ObjectMapper objectMapper;
    private ErrorResolver errorResolver = null;
    private Boolean registerTraceInterceptor;
    private boolean backwardsCompatible = true;
    private boolean rethrowExceptions = false;
    private boolean allowExtraParams = false;
    private boolean allowLessParams = false;
    private InvocationListener invocationListener = null;
    private HttpStatusCodeProvider httpStatusCodeProvider = null;
    private ConvertedParameterTransformer convertedParameterTransformer = null;

    //指定对外提供rpc服务的扫描包路径
    private static Set<String> servicePackages = new HashSet<>();

    /**
     * 添加要扫描的rpc服务接口实现类包路径
     *
     * @param pkg 包路径,会自动扫描其子包
     */
    public static void addImplScanPackage(String pkg) {
        servicePackages.add(pkg);
    }

    private static boolean isInPackage(String className, String beanName) {
        if (StringUtils.isEmpty(className)) {
            logger.debug("can't find the beanClassName for [{}]", beanName);
            return false;
        }
        if (servicePackages.size() == 0) {
            return true;
        }
        int end = 0;
        //以.为分隔，逐个从右向左匹配，获取到匹配度最高的服务实例名称
        while (!servicePackages.contains(className)) {
            end = className.lastIndexOf(".");
            if (end == -1) {
                break;
            }
            className = className.substring(0, end);
        }
        return servicePackages.contains(className);
    }

    private static void hasScanPackages() {
        if (servicePackages.size() == 0) {
            logger.debug("no clear packages to scan,scan all packages,this can cause find more rpc services than you " +
                    "want to provide!");
        }
    }

    /**
     * Finds the beans to expose
     * map.
     * <p>
     * Searches parent factories as well.
     */
    private static Map<String, String> findServiceBeanDefinitions(ConfigurableListableBeanFactory beanFactory) {
        final Map<String, String> serviceBeanNames = new HashMap<>();
        hasScanPackages();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            JsonRpcService jsonRpcPath = beanFactory.findAnnotationOnBean(beanName, JsonRpcService.class);
            if (hasServiceAnnotation(jsonRpcPath)) {
                if (!isInPackage(beanFactory.getBeanDefinition(beanName).getBeanClassName(), beanName)) {
                    continue;
                }
                String pathValue = jsonRpcPath.value();
                //默认使用bean名称作为路径
                if (StringUtils.isEmpty(pathValue)) {
                    pathValue = Util.className2Path(getServiceInterfaceName(beanFactory, beanName));
                }
                logger.debug("Found JSON-RPC path '{}' for bean [{}].", pathValue, beanName);
                if (isNotDuplicateService(serviceBeanNames, beanName, pathValue))
                    serviceBeanNames.put(pathValue, beanName);
            }
        }
        collectFromParentBeans(beanFactory, serviceBeanNames);
        return serviceBeanNames;
    }

    /**
     * 获取rpc服务的实现类对应的服务接口的名称
     *
     * @param beanFactory 工厂
     * @param beanName    服务实现类名称
     * @return 接口名称, 找不到时返回bean名称
     */
    private static String getServiceInterfaceName(ConfigurableListableBeanFactory beanFactory, String beanName) {
        BeanDefinition serviceBeanDefinition = beanFactory.getBeanDefinition(beanName);
        for (Class<?> currentInterface : getBeanInterfaces(serviceBeanDefinition, beanFactory.getBeanClassLoader())) {
            if (currentInterface.isAnnotationPresent(JsonRpcService.class)) {
                return currentInterface.getName();
            }
        }
        return beanName;
    }

    @SuppressWarnings("Convert2streamapi")
    private static void collectFromParentBeans(ConfigurableListableBeanFactory beanFactory, Map<String, String>
            serviceBeanNames) {
        BeanFactory parentBeanFactory = beanFactory.getParentBeanFactory();
        if (parentBeanFactory != null && ConfigurableListableBeanFactory.class.isInstance(parentBeanFactory)) {
            for (Entry<String, String> entry : findServiceBeanDefinitions((ConfigurableListableBeanFactory)
                    parentBeanFactory).entrySet()) {
                if (isNotDuplicateService(serviceBeanNames, entry.getKey(), entry.getValue()))
                    serviceBeanNames.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private static boolean isNotDuplicateService(Map<String, String> serviceBeanNames, String beanName, String
            pathValue) {
        if (serviceBeanNames.containsKey(pathValue)) {
            String otherBeanName = serviceBeanNames.get(pathValue);
            logger.debug("Duplicate JSON-RPC path specification: found {} on both [{}] and [{}].", pathValue,
                    beanName, otherBeanName);
            return false;
        }
        return true;
    }

    private static boolean hasServiceAnnotation(JsonRpcService jsonRpcPath) {
        return jsonRpcPath != null;
    }

    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        DefaultListableBeanFactory defaultListableBeanFactory = (DefaultListableBeanFactory) beanFactory;
        Map<String, String> servicePathToBeanName = findServiceBeanDefinitions(defaultListableBeanFactory);
        for (Entry<String, String> entry : servicePathToBeanName.entrySet()) {
            registerServiceProxy(defaultListableBeanFactory, makeUrlPath(entry.getKey()), entry.getValue());
        }
    }

    /**
     * To make the
     * {@link org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping}
     * export a bean automatically, the name should start with a '/'.
     */
    private String makeUrlPath(String servicePath) {
        return Util.addPrefixAndDistinct(servicePath);
    }

    /**
     * Registers the new beans with the bean factory.
     */
    private void registerServiceProxy(DefaultListableBeanFactory defaultListableBeanFactory, String servicePath,
                                      String serviceBeanName) {
        BeanDefinitionBuilder builder = BeanDefinitionBuilder.rootBeanDefinition(JsonServiceExporter.class)
                .addPropertyReference("service", serviceBeanName);
        BeanDefinition serviceBeanDefinition = findBeanDefinition(defaultListableBeanFactory, serviceBeanName);
        for (Class<?> currentInterface : getBeanInterfaces(serviceBeanDefinition, defaultListableBeanFactory
                .getBeanClassLoader())) {
            if (currentInterface.isAnnotationPresent(JsonRpcService.class)) {
                String serviceInterface = currentInterface.getName();
                logger.debug("Registering interface '{}' for JSON-RPC bean [{}].", serviceInterface, serviceBeanName);
                builder.addPropertyValue("serviceInterface", serviceInterface);
                break;
            }
        }
        if (objectMapper != null) {
            builder.addPropertyValue("objectMapper", objectMapper);
        }

        if (errorResolver != null) {
            builder.addPropertyValue("errorResolver", errorResolver);
        }

        if (invocationListener != null) {
            builder.addPropertyValue("invocationListener", invocationListener);
        }

        if (registerTraceInterceptor != null) {
            builder.addPropertyValue("registerTraceInterceptor", registerTraceInterceptor);
        }

        if (httpStatusCodeProvider != null) {
            builder.addPropertyValue("httpStatusCodeProvider", httpStatusCodeProvider);
        }

        if (convertedParameterTransformer != null) {
            builder.addPropertyValue("convertedParameterTransformer", convertedParameterTransformer);
        }

        builder.addPropertyValue("backwardsCompatible", backwardsCompatible);
        builder.addPropertyValue("rethrowExceptions", rethrowExceptions);
        builder.addPropertyValue("allowExtraParams", allowExtraParams);
        builder.addPropertyValue("allowLessParams", allowLessParams);

        defaultListableBeanFactory.registerBeanDefinition(servicePath, builder.getBeanDefinition());
    }

    /**
     * Find a {@link BeanDefinition} in the {@link BeanFactory} or it's parents.
     */
    private BeanDefinition findBeanDefinition(ConfigurableListableBeanFactory beanFactory, String serviceBeanName) {
        if (beanFactory.containsLocalBean(serviceBeanName)) return beanFactory.getBeanDefinition(serviceBeanName);
        BeanFactory parentBeanFactory = beanFactory.getParentBeanFactory();
        if (parentBeanFactory != null && ConfigurableListableBeanFactory.class.isInstance(parentBeanFactory))
            return findBeanDefinition((ConfigurableListableBeanFactory) parentBeanFactory, serviceBeanName);
        throw new RuntimeException(format("Bean with name '%s' can no longer be found.", serviceBeanName));
    }

    private static Class<?>[] getBeanInterfaces(BeanDefinition serviceBeanDefinition, ClassLoader beanClassLoader) {
        String beanClassName = serviceBeanDefinition.getBeanClassName();
        try {
            Class<?> beanClass = forName(beanClassName, beanClassLoader);
            return getAllInterfacesForClass(beanClass, beanClassLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new RuntimeException(format("Cannot find bean class '%s'.", beanClassName), e);
        }
    }

    /**
     * @param objectMapper the objectMapper to set
     */
    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param errorResolver the errorResolver to set
     */
    public void setErrorResolver(ErrorResolver errorResolver) {
        this.errorResolver = errorResolver;
    }

    /**
     * @param backwardsCompatible the backwardsCompatible to set
     */
    public void setBackwardsCompatible(boolean backwardsCompatible) {
        this.backwardsCompatible = backwardsCompatible;
    }

    /**
     * @param rethrowExceptions the rethrowExceptions to set
     */
    public void setRethrowExceptions(boolean rethrowExceptions) {
        this.rethrowExceptions = rethrowExceptions;
    }

    /**
     * @param allowExtraParams the allowExtraParams to set
     */
    public void setAllowExtraParams(boolean allowExtraParams) {
        this.allowExtraParams = allowExtraParams;
    }

    /**
     * @param allowLessParams the allowLessParams to set
     */
    public void setAllowLessParams(boolean allowLessParams) {
        this.allowLessParams = allowLessParams;
    }

    /**
     * See {@link org.springframework.remoting.support.RemoteExporter#setRegisterTraceInterceptor(boolean)}
     *
     * @param registerTraceInterceptor the registerTraceInterceptor value to set
     */
    public void setRegisterTraceInterceptor(boolean registerTraceInterceptor) {
        this.registerTraceInterceptor = registerTraceInterceptor;
    }

    /**
     * @param invocationListener the invocationListener to set
     */
    public void setInvocationListener(InvocationListener invocationListener) {
        this.invocationListener = invocationListener;
    }

    /**
     * @param httpStatusCodeProvider the HttpStatusCodeProvider to set
     */
    public void setHttpStatusCodeProvider(HttpStatusCodeProvider httpStatusCodeProvider) {
        this.httpStatusCodeProvider = httpStatusCodeProvider;
    }

    /**
     * @param convertedParameterTransformer the convertedParameterTransformer to set
     */
    public void setConvertedParameterTransformer(ConvertedParameterTransformer convertedParameterTransformer) {
        this.convertedParameterTransformer = convertedParameterTransformer;
    }
}
