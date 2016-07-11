package com.googlecode.jsonrpc4j.spring;

import static java.lang.String.format;
import static org.springframework.util.ClassUtils.convertClassNameToResourcePath;
import static org.springframework.util.ResourceUtils.CLASSPATH_URL_PREFIX;

import com.googlecode.jsonrpc4j.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import com.googlecode.jsonrpc4j.JsonRpcService;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Auto-creates proxies for service interfaces annotated with {@link JsonRpcService}.
 */
@SuppressWarnings("unused")
public class AutoJsonRpcClientProxyCreator implements BeanFactoryPostProcessor, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(AutoJsonRpcClientProxyCreator.class);
    private ApplicationContext applicationContext;
    private String scanPackage;
    private URL baseUrl;
    private ObjectMapper objectMapper;
    private String contentType;

    /**
     * 服务实例名称，与baseURL二选一，这里是自动发现服务时使用
     */
    private String serviceId;

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        SimpleMetadataReaderFactory metadataReaderFactory = new SimpleMetadataReaderFactory(applicationContext);
        DefaultListableBeanFactory defaultListableBeanFactory = (DefaultListableBeanFactory) beanFactory;
        String resolvedPath = resolvePackageToScan();
        logger.debug("Scanning '{}' for JSON-RPC service interfaces.", resolvedPath);
        try {
            for (Resource resource : applicationContext.getResources(resolvedPath)) {
                if (resource.isReadable()) {
                    MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(resource);
                    ClassMetadata classMetadata = metadataReader.getClassMetadata();
                    AnnotationMetadata annotationMetadata = metadataReader.getAnnotationMetadata();
                    String jsonRpcPathAnnotation = JsonRpcService.class.getName();
                    if (annotationMetadata.isAnnotated(jsonRpcPathAnnotation)) {
                        String className = classMetadata.getClassName();
                        String path = (String) annotationMetadata.getAnnotationAttributes(jsonRpcPathAnnotation).get("value");
                        //默认类名称作为路径
                        if (StringUtils.isEmpty(path)) {
                            path = Util.className2Path(className);
                        }
                        logger.debug("Found JSON-RPC service to proxy [{}] on path '{}'.", className, path);
                        registerJsonProxyBean(defaultListableBeanFactory, className, path);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(format("Cannot scan package '%s' for classes.", resolvedPath), e);
        }
    }

    /**
     * Converts the scanPackage to something that the resource loader can handleRequest.
     */
    private String resolvePackageToScan() {
        return CLASSPATH_URL_PREFIX + convertClassNameToResourcePath(scanPackage) + "/**/*.class";
    }

    /**
     * Registers a new proxy bean with the bean factory.
     */
    private void registerJsonProxyBean(DefaultListableBeanFactory defaultListableBeanFactory, String className, String path) {
        BeanDefinitionBuilder beanDefinitionBuilder = BeanDefinitionBuilder
                .rootBeanDefinition(JsonProxyFactoryBean.class)
                .addPropertyValue("serviceInterface", className);

        if (StringUtils.isEmpty(serviceId)) {
            beanDefinitionBuilder.addPropertyValue("serviceUrl", appendBasePath(path));
        } else {
            logger.debug("自动发现rpc服务,serviceId={},path={}", serviceId, path);
            beanDefinitionBuilder.addPropertyValue("serviceId", serviceId).addPropertyValue("serviceUrl", path);
        }

        if (objectMapper != null) {
            beanDefinitionBuilder.addPropertyValue("objectMapper", objectMapper);
        }

        if (contentType != null) {
            beanDefinitionBuilder.addPropertyValue("contentType", contentType);
        }

        defaultListableBeanFactory.registerBeanDefinition(className + "-clientProxy", beanDefinitionBuilder.getBeanDefinition());
    }

    /**
     * Appends the base path to the path found in the interface.
     */
    private String appendBasePath(String path) {
        try {
            return new URL(baseUrl, path).toString();
        } catch (MalformedURLException e) {
            throw new RuntimeException(format("Cannot combine URLs '%s' and '%s' to valid URL.", baseUrl, path), e);
        }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    public void setBaseUrl(URL baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setScanPackage(String scanPackage) {
        this.scanPackage = scanPackage;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void setContentType(String contextType) {
        this.contentType = contextType;
    }

    public String getServiceId() {
        return serviceId;
    }

    /**
     * @param serviceId 服务实例名称
     */
    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }
}
