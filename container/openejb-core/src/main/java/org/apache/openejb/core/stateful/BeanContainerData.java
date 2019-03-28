package org.apache.openejb.core.stateful;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.management.ObjectName;

import org.apache.openejb.util.Index;

public class BeanContainerData {
	private final Index<Method, MethodType> methodIndex;
    public final List<ObjectName> jmxNames = new ArrayList<>();

    public BeanContainerData(final Index<Method, MethodType> methodIndex) {
        this.methodIndex = methodIndex;
    }

    public Index<Method, MethodType> getMethodIndex() {
        return methodIndex;
    }
}
