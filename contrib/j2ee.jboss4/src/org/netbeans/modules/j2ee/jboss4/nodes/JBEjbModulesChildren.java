/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.netbeans.modules.j2ee.jboss4.nodes;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.enterprise.deploy.shared.ModuleType;
import javax.enterprise.deploy.spi.Target;
import javax.enterprise.deploy.spi.TargetModuleID;
import javax.enterprise.deploy.spi.exceptions.TargetException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectInstance;
import javax.management.ObjectName;
import javax.management.QueryExp;
import org.netbeans.modules.j2ee.jboss4.JBDeploymentManager;
import org.netbeans.modules.j2ee.jboss4.JBRemoteAction;
import org.netbeans.modules.j2ee.jboss4.JBoss5ProfileServiceProxy;
import org.netbeans.modules.j2ee.jboss4.nodes.actions.Refreshable;
import org.openide.nodes.Node;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;

/**
 * It describes children nodes of the EJB Modules node. Implements
 * Refreshable interface and due to it can be refreshed via ResreshModulesAction.
 *
 * @author Michal Mocnak
 */
public class JBEjbModulesChildren extends JBAsyncChildren implements Refreshable {

    private static final Logger LOGGER = Logger.getLogger(JBEjbModulesChildren.class.getName());
    
    private final JBAbilitiesSupport abilitiesSupport;
    
    private final Lookup lookup;
    
    public JBEjbModulesChildren(Lookup lookup) {
        this.lookup = lookup;
        this.abilitiesSupport = new JBAbilitiesSupport(lookup);
    }
    
    public void updateKeys(){
        setKeys(new Object[] {Util.WAIT_NODE});
        
        getExecutorService().submit(abilitiesSupport.isJB7x() ? new JBoss7EjbApplicationNodeUpdater() : new Runnable() {
            public void run() {
                List keys = new LinkedList();
                JBDeploymentManager dm = lookup.lookup(JBDeploymentManager.class);
                addEjbModules(dm, keys);
                addEJB3Modules(dm, keys);
                
                setKeys(keys);
            }
        }, 0);
        
    }
    
    private void addEjbModules(JBDeploymentManager dm, final List keys) {
        try {
            dm.invokeRemoteAction(new JBRemoteAction<Void>() {

                @Override
                public Void action(MBeanServerConnection connection, JBoss5ProfileServiceProxy profileService) throws Exception {
                    String propertyName;
                    Object searchPattern;
                    if (abilitiesSupport.isRemoteManagementSupported()
                            && (abilitiesSupport.isJB4x() || abilitiesSupport.isJB6x())) {
                        propertyName = "name"; // NOI18N
                        searchPattern = new ObjectName("jboss.management.local:j2eeType=EJBModule,J2EEApplication=null,*"); // NOI18N
                    } else {
                        propertyName = "module"; // NOI18N
                        searchPattern = new ObjectName("jboss.j2ee:service=EjbModule,*"); // NOI18N
                    }

                    Method method = connection.getClass().getMethod("queryMBeans", new Class[]{ObjectName.class, QueryExp.class});
                    method = Util.fixJava4071957(method);
                    Set managedObj = (Set) method.invoke(connection, new Object[]{searchPattern, null}); // NOI18N

                    Iterator it = managedObj.iterator();

                    // Query results processing
                    while (it.hasNext()) {
                        ObjectName elem = ((ObjectInstance) it.next()).getObjectName();
                        String name = elem.getKeyProperty(propertyName);
                        keys.add(new JBEjbModuleNode(name, lookup));
                    }
                    return null;
                }
            });
        } catch (ExecutionException ex) {
            LOGGER.log(Level.INFO, null, ex);
        }
    }
    
    // JBoss 5 only ?
    private void addEJB3Modules(JBDeploymentManager dm, final List keys) {
        try {
            dm.invokeRemoteAction(new JBRemoteAction<Void>() {

                @Override
                public Void action(MBeanServerConnection connection, JBoss5ProfileServiceProxy profileService) throws Exception {
                    ObjectName searchPattern = new ObjectName("jboss.j2ee:service=EJB3,*"); // NOI18N
                    Method method = connection.getClass().getMethod("queryMBeans", new Class[] {ObjectName.class, QueryExp.class});
                    method = Util.fixJava4071957(method);
                    Set managedObj = (Set) method.invoke(connection, new Object[] {searchPattern, null}); // NOI18N

                    Iterator it = managedObj.iterator();

                    // Query results processing
                    while(it.hasNext()) {
                        try {
                            ObjectName elem = ((ObjectInstance) it.next()).getObjectName();
                            String name = elem.getKeyProperty("module"); // NOI18N
                            if (name != null) {
                                keys.add(new JBEjbModuleNode(name, lookup, true));
                            }
                        } catch (Exception ex) {
                            LOGGER.log(Level.INFO, null, ex);
                        }
                    }
                    return null;
                }
            });
        } catch (ExecutionException ex) {
            LOGGER.log(Level.INFO, null, ex);
        }
    }
    class  JBoss7EjbApplicationNodeUpdater implements Runnable {

         List keys = new ArrayList();

        @Override
        public void run() {
            try {
                final JBDeploymentManager dm = (JBDeploymentManager) lookup.lookup(JBDeploymentManager.class);
                dm.invokeLocalAction(new Callable<Void>() {

                    @Override
                    public Void call() {
                        try {
                            Target[] targets = dm.getTargets();
                            ModuleType moduleType = ModuleType.EJB;

                            //Get all deployed EAR files.
                            TargetModuleID[] modules = dm.getAvailableModules(moduleType, targets);
                            // Module list may be null if nothing is deployed.
                            if (modules != null) {
                                for (int intModule = 0; intModule < modules.length; intModule++) {
                                    keys.add(new JBEjbModuleNode(modules[intModule].getModuleID(), lookup)); // TODO: how to check ejb3?
                                }
                            }
                        } catch (TargetException ex) {
                            Exceptions.printStackTrace(ex);
                        } catch (IllegalStateException ex) {
                            Exceptions.printStackTrace(ex);
                        }
                        return null;
                    }
                });
            } catch (Exception ex) {
                LOGGER.log(Level.INFO, null, ex);
            }

            setKeys(keys);
        }
    }
    protected void addNotify() {
        updateKeys();
    }
    
    protected void removeNotify() {
        setKeys(java.util.Collections.EMPTY_SET);
    }
    
    protected org.openide.nodes.Node[] createNodes(Object key) {
        if (key instanceof JBEjbModuleNode){
            return new Node[]{(JBEjbModuleNode)key};
        }
        
        if (key instanceof String && key.equals(Util.WAIT_NODE)){
            return new Node[]{Util.createWaitNode()};
        }
        
        return null;
    }
    
}
