package org.apache.openejb.core.stateful;

import org.apache.openejb.ApplicationException;
import org.apache.openejb.BeanContext;
import org.apache.openejb.ContainerType;
import org.apache.openejb.InterfaceType;
import org.apache.openejb.InvalidateReferenceException;
import org.apache.openejb.OpenEJBException;
import org.apache.openejb.OpenEJBRuntimeException;
import org.apache.openejb.ProxyInfo;
import org.apache.openejb.RpcContainer;
import org.apache.openejb.SystemException;
import org.apache.openejb.cdi.CdiEjbBean;
import org.apache.openejb.cdi.CurrentCreationalContext;
import org.apache.openejb.core.ExceptionType;
import org.apache.openejb.core.InstanceContext;
import org.apache.openejb.core.Operation;
import org.apache.openejb.core.ThreadContext;
import org.apache.openejb.core.interceptor.InterceptorData;
import org.apache.openejb.core.interceptor.InterceptorStack;
import org.apache.openejb.core.security.AbstractSecurityService;
import org.apache.openejb.core.stateful.Cache.CacheFilter;
import org.apache.openejb.core.stateful.Cache.CacheListener;
import org.apache.openejb.core.transaction.BeanTransactionPolicy;
import org.apache.openejb.core.transaction.BeanTransactionPolicy.SuspendedTransaction;
import org.apache.openejb.core.transaction.EjbTransactionUtil;
import org.apache.openejb.core.transaction.EjbUserTransaction;
import org.apache.openejb.core.transaction.JtaTransactionPolicy;
import org.apache.openejb.core.transaction.TransactionPolicy;
import org.apache.openejb.core.transaction.TransactionPolicy.TransactionSynchronization;
import org.apache.openejb.loader.SystemInstance;
import org.apache.openejb.monitoring.LocalMBeanServer;
import org.apache.openejb.monitoring.ManagedMBean;
import org.apache.openejb.monitoring.ObjectNameBuilder;
import org.apache.openejb.monitoring.StatsInterceptor;
import org.apache.openejb.persistence.EntityManagerAlreadyRegisteredException;
import org.apache.openejb.persistence.JtaEntityManagerRegistry;
import org.apache.openejb.spi.SecurityService;
import org.apache.openejb.util.Duration;
import org.apache.openejb.util.Index;
import org.apache.openejb.util.LogCategory;
import org.apache.openejb.util.Logger;

import javax.ejb.ConcurrentAccessTimeoutException;
import javax.ejb.EJBAccessException;
import javax.ejb.EJBContext;
import javax.ejb.EJBException;
import javax.ejb.EJBHome;
import javax.ejb.EJBLocalHome;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import javax.enterprise.context.Dependent;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.SynchronizationType;
import javax.security.auth.login.LoginException;
import javax.transaction.Transaction;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;
import java.rmi.dgc.VMID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class ContainerBeanDeplyoer {
	
	private static final Logger logger = Logger.getInstance(LogCategory.OPENEJB, "org.apache.openejb.util.resources");
	
	protected final Map<Object, BeanContext> deploymentsById = new HashMap<>();
	
	public Map<Method, MethodType> getLifecycleMethodsOfInterface(final BeanContext beanContext) {
        final Map<Method, MethodType> methods = new HashMap<>();

        try {
            methods.put(BeanContext.Removable.class.getDeclaredMethod("$$remove"), MethodType.REMOVE);
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException("Internal code change: BeanContext.Removable.$$remove() method was deleted", e);
        }

        final List<Method> removeMethods = beanContext.getRemoveMethods();
        for (final Method removeMethod : removeMethods) {
            methods.put(removeMethod, MethodType.REMOVE);

            for (final Class businessLocal : beanContext.getBusinessLocalInterfaces()) {
                try {
                    final Method method = businessLocal.getMethod(removeMethod.getName(), removeMethod.getParameterTypes());
                    methods.put(method, MethodType.REMOVE);
                } catch (final NoSuchMethodException ignore) {
                    // no-op
                }
            }

            for (final Class businessRemote : beanContext.getBusinessRemoteInterfaces()) {
                try {
                    final Method method = businessRemote.getMethod(removeMethod.getName(), removeMethod.getParameterTypes());
                    methods.put(method, MethodType.REMOVE);
                } catch (final NoSuchMethodException ignore) {
                    // no-op
                }
            }
        }

        final Class legacyRemote = beanContext.getRemoteInterface();
        if (legacyRemote != null) {
            try {
                final Method method = legacyRemote.getMethod("remove");
                methods.put(method, MethodType.REMOVE);
            } catch (final NoSuchMethodException ignore) {
                // no-op
            }
        }

        final Class legacyLocal = beanContext.getLocalInterface();
        if (legacyLocal != null) {
            try {
                final Method method = legacyLocal.getMethod("remove");
                methods.put(method, MethodType.REMOVE);
            } catch (final NoSuchMethodException ignore) {
                // no-op
            }
        }

        final Class businessLocalHomeInterface = beanContext.getBusinessLocalInterface();
        if (businessLocalHomeInterface != null) {
            for (final Method method : BeanContext.BusinessLocalHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        final Class businessLocalBeanHomeInterface = beanContext.getBusinessLocalBeanInterface();
        if (businessLocalBeanHomeInterface != null) {
            for (final Method method : BeanContext.BusinessLocalBeanHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        final Class businessRemoteHomeInterface = beanContext.getBusinessRemoteInterface();
        if (businessRemoteHomeInterface != null) {
            for (final Method method : BeanContext.BusinessRemoteHome.class.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        final Class homeInterface = beanContext.getHomeInterface();
        if (homeInterface != null) {
            for (final Method method : homeInterface.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }

        final Class localHomeInterface = beanContext.getLocalHomeInterface();
        if (localHomeInterface != null) {
            for (final Method method : localHomeInterface.getMethods()) {
                if (method.getName().startsWith("create")) {
                    methods.put(method, MethodType.CREATE);
                } else if (method.getName().equals("remove")) {
                    methods.put(method, MethodType.REMOVE);
                }
            }
        }
        return methods;
    }
	
	public synchronized void undeploy(final BeanContext beanContext) throws OpenEJBException {
    	final BeanContainerData data = (BeanContainerData) beanContext.getContainerData();

        final MBeanServer server = LocalMBeanServer.get();
        for (final ObjectName objectName : data.jmxNames) {
            try {
                server.unregisterMBean(objectName);
            } catch (final Exception e) {
                logger.error("Unable to unregister MBean " + objectName);
            }
        }

        deploymentsById.remove(beanContext.getDeploymentID());
        beanContext.setContainer(null);
        beanContext.setContainerData(null);
        
    }
	
	public synchronized void deploy(final BeanContext beanContext, SessionContext sessionContext) throws OpenEJBException {
    	final Map<Method, MethodType> methods = getLifecycleMethodsOfInterface(beanContext);

        deploymentsById.put(beanContext.getDeploymentID(), beanContext);
        
        final BeanContainerData data = new BeanContainerData(new Index<>(methods));
        beanContext.setContainerData(data);

        // Create stats interceptor
        if (StatsInterceptor.isStatsActivated()) {
            final StatsInterceptor stats = new StatsInterceptor(beanContext.getBeanClass());
            beanContext.addFirstSystemInterceptor(stats);

            final MBeanServer server = LocalMBeanServer.get();

            final ObjectNameBuilder jmxName = new ObjectNameBuilder("openejb.management");
            jmxName.set("J2EEServer", "openejb");
            jmxName.set("J2EEApplication", null);
            jmxName.set("EJBModule", beanContext.getModuleID());
            jmxName.set("StatefulSessionBean", beanContext.getEjbName());
            jmxName.set("j2eeType", "");
            jmxName.set("name", beanContext.getEjbName());

            // register the invocation stats interceptor
            try {
                final ObjectName objectName = jmxName.set("j2eeType", "Invocations").build();
                if (server.isRegistered(objectName)) {
                    server.unregisterMBean(objectName);
                }
                server.registerMBean(new ManagedMBean(stats), objectName);
                data.jmxNames.add(objectName);
            } catch (final Exception e) {
                logger.error("Unable to register MBean ", e);
            }
        }

        try {
            final Context context = beanContext.getJndiEnc();
            context.bind("comp/EJBContext", sessionContext);
        } catch (final NamingException e) {
            throw new OpenEJBException("Failed to bind EJBContext", e);
        }

        beanContext.set(EJBContext.class, sessionContext);
    }
	
	public synchronized BeanContext[] getDeployedBeanContexts() {
    	return deploymentsById.values().toArray(new BeanContext[deploymentsById.size()]);
    }
	
	public synchronized BeanContext getDeployedBeanContextById(final Object deploymentID) {
    	return deploymentsById.get(deploymentID);
    }
	
	
	public static class BeanContextFilter implements CacheFilter<Instance>, Serializable {
        private final String id;

        public BeanContextFilter(final String id) {
            this.id = id;
        }

        @Override
        public boolean matches(final Instance instance) {
            return instance.beanContext.getId().equals(id);
        }
    }
}
