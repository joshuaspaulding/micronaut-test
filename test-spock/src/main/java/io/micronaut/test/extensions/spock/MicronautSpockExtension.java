/*
 * Copyright 2017-2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.micronaut.test.extensions.spock;

import io.micronaut.aop.InterceptedProxy;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.MethodInjectionPoint;
import io.micronaut.test.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.AbstractMicronautExtension;
import io.micronaut.test.support.TestPropertyProvider;
import org.spockframework.mock.MockUtil;
import org.spockframework.runtime.InvalidSpecException;
import org.spockframework.runtime.extension.IAnnotationDrivenExtension;
import org.spockframework.runtime.extension.IMethodInvocation;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.FieldInfo;
import org.spockframework.runtime.model.MethodInfo;
import org.spockframework.runtime.model.SpecInfo;
import spock.lang.Specification;

import javax.inject.Inject;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Extension for Spock.
 *
 * @author graemerocher
 * @since 1.0
 */
public class MicronautSpockExtension extends AbstractMicronautExtension<IMethodInvocation> implements IAnnotationDrivenExtension<MicronautTest> {

    private Queue<Object> createdMocks = new ConcurrentLinkedDeque<>();
    private MockUtil mockUtil = new MockUtil();

    @Override
    public void visitSpecAnnotation(MicronautTest annotation, SpecInfo spec) {

        spec.addSetupSpecInterceptor(invocation -> {
                    beforeClass(invocation, spec.getReflection(), spec.getAnnotation(MicronautTest.class));
                    if (specDefinition == null) {
                        if (!isTestSuiteBeanPresent(spec.getReflection())) {
                            throw new InvalidSpecException(MISCONFIGURED_MESSAGE);
                        } else {
                            final List<FeatureInfo> features = invocation.getSpec().getFeatures();
                            for (FeatureInfo feature : features) {
                                feature.setSkipped(true);
                            }
                        }
                    } else {
                        List<FieldInfo> fields = spec.getFields();
                        for (FieldInfo field : fields) {
                            if (field.isShared() && field.getAnnotation(Inject.class) != null) {
                                applicationContext.inject(invocation.getSharedInstance());
                                break;
                            }
                        }
                    }
                    invocation.proceed();
                }
        );

        spec.addCleanupSpecInterceptor(this::afterClass);

        spec.addSetupInterceptor(invocation -> {
            final Object instance = invocation.getInstance();
            final Method method = invocation.getFeature().getFeatureMethod().getReflection();
            beforeEach(invocation, instance, method);
            for (Object createdMock : createdMocks) {
                mockUtil.attachMock(createdMock, (Specification) instance);
            }
            begin();
            invocation.proceed();
        });

        spec.addCleanupInterceptor(invocation -> {
            for (Object createdMock : createdMocks) {
                mockUtil.detachMock(createdMock);
            }
            createdMocks.clear();
            afterEach(invocation);
            commit();
            rollback();
            invocation.proceed();
        });
    }

    @Override
    public void visitFeatureAnnotation(MicronautTest annotation, FeatureInfo feature) {
        throw new InvalidSpecException("@%s may not be applied to feature methods")
                .withArgs(annotation.annotationType().getSimpleName());
    }

    @Override
    public void visitFixtureAnnotation(MicronautTest annotation, MethodInfo fixtureMethod) {
        throw new InvalidSpecException("@%s may not be applied to fixture methods")
                .withArgs(annotation.annotationType().getSimpleName());

    }

    @Override
    public void visitFieldAnnotation(MicronautTest annotation, FieldInfo field) {
        throw new InvalidSpecException("@%s may not be applied to fields")
                .withArgs(annotation.annotationType().getSimpleName());

    }

    @Override
    public void visitSpec(SpecInfo spec) {
        // no-op
    }

    @Override
    protected void resolveTestProperties(IMethodInvocation context, MicronautTest testAnnotation, Map<String, Object> testProperties) {
        Object sharedInstance = context.getSharedInstance();
        if (sharedInstance instanceof TestPropertyProvider) {
            Map<String, String> properties = ((TestPropertyProvider) sharedInstance).getProperties();
            if (CollectionUtils.isNotEmpty(properties)) {
                testProperties.putAll(properties);
            }
        }
    }

    @Override
    protected void startApplicationContext() {
        applicationContext.registerSingleton((BeanCreatedEventListener) event -> {
            final Object bean = event.getBean();
            if (mockUtil.isMock(bean)) {
                createdMocks.add(bean);
            }
            return bean;
        });

        super.startApplicationContext();
    }

    @Override
    protected void alignMocks(IMethodInvocation context, Object instance) {
        for (MethodInjectionPoint injectedMethod : specDefinition.getInjectedMethods()) {
            final Argument<?>[] args = injectedMethod.getArguments();
            if (args.length == 1) {
                final Optional<FieldInfo> fld = context.getSpec().getFields().stream().filter(f -> f.getName().equals(args[0].getName())).findFirst();
                if (fld.isPresent()) {
                    final FieldInfo fieldInfo = fld.get();
                    if (applicationContext.resolveMetadata(fieldInfo.getType()).isAnnotationPresent(MockBean.class)) {
                        final Object proxy = fieldInfo.readValue(
                                instance
                        );
                        if (proxy instanceof InterceptedProxy) {
                            final InterceptedProxy interceptedProxy = (InterceptedProxy) proxy;
                            final Object target = interceptedProxy.interceptedTarget();
                            fieldInfo.writeValue(instance, target);
                        }

                    }
                }
            }
        }
    }

}
