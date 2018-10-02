package io.micronaut.test.junit5.annotation;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.inject.BeanDefinition;

import java.util.Optional;

public class TestActiveCondition implements Condition {
    public static final String ACTIVE_MOCKS = "micronaut.test.junit5.active.mocks";
    public static final String ACTIVE_SPEC_NAME = "micronaut.test.active.test";

    @Override
    public boolean matches(ConditionContext context) {
        if (context.getComponent() instanceof BeanDefinition) {
            BeanDefinition<?> definition = (BeanDefinition<?>) context.getComponent();
            final BeanContext beanContext = context.getBeanContext();
            if (beanContext instanceof ApplicationContext) {
                final Optional<Class<?>> declaringType = definition.getDeclaringType();
                ApplicationContext applicationContext = (ApplicationContext) beanContext;
                if (definition.isAnnotationPresent(MockBean.class) && declaringType.isPresent()) {
                    final String activeSpecName = applicationContext.get(ACTIVE_SPEC_NAME, String.class).orElse(null);
                    final Class<?> declaringTypeClass = declaringType.get();
                    String declaringTypeName = declaringTypeClass.getName();
                    if (activeSpecName != null) {
                        if (definition.isProxy()) {
                            final String packageName = NameUtils.getPackageName(activeSpecName);
                            final String simpleName = NameUtils.getSimpleName(activeSpecName);
                            return declaringTypeName.startsWith(packageName + ".$" + simpleName);
                        } else {
                            return activeSpecName.equals(declaringTypeName) || declaringTypeName.startsWith(activeSpecName + "$");
                        }
                    } else {
                        return true;
                    }
                } else {
                    final String activeSpecName = applicationContext.get(ACTIVE_SPEC_NAME, String.class).orElse(null);
                    return activeSpecName != null && activeSpecName.equals(definition.getBeanType().getName());
                }
            } else {
                return false;
            }
        } else {
            return true;
        }
    }
}