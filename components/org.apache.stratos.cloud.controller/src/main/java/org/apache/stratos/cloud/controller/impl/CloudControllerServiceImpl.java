/*
 * Licensed to the Apache Software Foundation (ASF) under one 
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.cloud.controller.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.cloud.controller.concurrent.ThreadExecutor;
import org.apache.stratos.cloud.controller.deployment.partition.Partition;
import org.apache.stratos.cloud.controller.exception.*;
import org.apache.stratos.cloud.controller.interfaces.CloudControllerService;
import org.apache.stratos.cloud.controller.interfaces.Iaas;
import org.apache.stratos.cloud.controller.persist.Deserializer;
import org.apache.stratos.cloud.controller.pojo.*;
import org.apache.stratos.cloud.controller.publisher.CartridgeInstanceDataPublisher;
import org.apache.stratos.cloud.controller.registry.RegistryManager;
import org.apache.stratos.cloud.controller.runtime.FasterLookUpDataHolder;
import org.apache.stratos.cloud.controller.topology.TopologyBuilder;
import org.apache.stratos.cloud.controller.topology.TopologyManager;
import org.apache.stratos.cloud.controller.util.CloudControllerConstants;
import org.apache.stratos.cloud.controller.util.CloudControllerUtil;
import org.apache.stratos.cloud.controller.validate.interfaces.PartitionValidator;
import org.apache.stratos.messaging.domain.topology.Member;
import org.apache.stratos.messaging.domain.topology.MemberStatus;
import org.apache.stratos.messaging.util.Constants;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.rest.ResourceNotFoundException;
import org.wso2.carbon.registry.core.exceptions.RegistryException;

import java.util.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloud Controller Service is responsible for starting up new server instances,
 * terminating already started instances, providing pending instance count etc.
 * 
 */
public class CloudControllerServiceImpl implements CloudControllerService {

	private static final Log log = LogFactory
			.getLog(CloudControllerServiceImpl.class);
	private FasterLookUpDataHolder dataHolder = FasterLookUpDataHolder
			.getInstance();

	public CloudControllerServiceImpl() {
		// acquire serialized data from registry
		acquireData();
	}

	private void acquireData() {

		Object obj = RegistryManager.getInstance().retrieve();
		if (obj != null) {
			try {
				Object dataObj = Deserializer
						.deserializeFromByteArray((byte[]) obj);
				if (dataObj instanceof FasterLookUpDataHolder) {
					FasterLookUpDataHolder serializedObj = (FasterLookUpDataHolder) dataObj;
					FasterLookUpDataHolder currentData = FasterLookUpDataHolder
							.getInstance();

					// assign necessary data
					currentData.setClusterIdToContext(serializedObj.getClusterIdToContext());
					currentData.setMemberIdToContext(serializedObj.getMemberIdToContext());
					currentData.setClusterIdToMemberContext(serializedObj.getClusterIdToMemberContext());
					currentData.setCartridges(serializedObj.getCartridges());

					if(log.isDebugEnabled()) {
					    
					    log.debug("Cloud Controller Data is retrieved from registry.");
					}
				} else {
				    if(log.isDebugEnabled()) {
				        
				        log.debug("Cloud Controller Data cannot be found in registry.");
				    }
				}
			} catch (Exception e) {

				String msg = "Unable to acquire data from Registry. Hence, any historical data will not get reflected.";
				log.warn(msg, e);
			}

		}
	}

    public void deployCartridgeDefinition(CartridgeConfig cartridgeConfig) throws InvalidCartridgeDefinitionException, 
    InvalidIaasProviderException, IllegalArgumentException {

        if (cartridgeConfig == null) {
            String msg = "Invalid Cartridge Definition: Definition is null.";
            log.error(msg);
            throw new IllegalArgumentException(msg);

        }

        if(log.isDebugEnabled()){
            log.debug("Cartridge definition: " + cartridgeConfig.toString());
        }

        Cartridge cartridge = null;
        try {
            cartridge = CloudControllerUtil.toCartridge(cartridgeConfig);
        } catch (Exception e) {
            String msg =
                         "Invalid Cartridge Definition: Cartridge Type: " +
                                 cartridgeConfig.getType()+
                                 ". Cause: Cannot instantiate a Cartridge Instance with the given Config. "+e.getMessage();
            log.error(msg, e);
            throw new InvalidCartridgeDefinitionException(msg, e);
        }

        List<IaasProvider> iaases = cartridge.getIaases();
        
        if (iaases == null || iaases.isEmpty()) {
            String msg =
                         "Invalid Cartridge Definition: Cartridge Type: " +
                                 cartridgeConfig.getType()+
                                 ". Cause: Iaases of this Cartridge is null or empty.";
            log.error(msg);
            throw new InvalidCartridgeDefinitionException(msg);
        }
        
        for (IaasProvider iaasProvider : iaases) {
            CloudControllerUtil.getIaas(iaasProvider);
        }
        
        // TODO transaction begins
        String cartridgeType = cartridge.getType();
        if(dataHolder.getCartridge(cartridgeType) != null) {
            if (dataHolder.getCartridges().remove(cartridge)) {
                log.info("Successfully undeployed the Cartridge definition: " + cartridgeType);
            }
        }
        
        dataHolder.addCartridge(cartridge);
        
        // persist
        persist();

        List<Cartridge> cartridgeList = new ArrayList<Cartridge>();
        cartridgeList.add(cartridge);

        TopologyBuilder.handleServiceCreated(cartridgeList);
        // transaction ends
        
        log.info("Successfully deployed the Cartridge definition: " + cartridgeType);
    }

    public void undeployCartridgeDefinition(String cartridgeType) throws InvalidCartridgeTypeException {

        Cartridge cartridge = null;
        if((cartridge = dataHolder.getCartridge(cartridgeType)) != null) {
            if (dataHolder.getCartridges().remove(cartridge)) {
                persist();
                if(log.isInfoEnabled()) {
                    log.info("Successfully undeployed the Cartridge definition: " + cartridgeType);
                }
                return;
            }
        }
        String msg = "Cartridge [type] "+cartridgeType+" is not a deployed Cartridge type.";
        log.error(msg);
        throw new InvalidCartridgeTypeException(msg);
    }
    
    @Override
    public MemberContext startInstance(MemberContext memberContext) throws IllegalArgumentException,
        UnregisteredCartridgeException, InvalidIaasProviderException, IllegalStateException {

        if (memberContext == null) {
            String msg = "Instance start-up failed. Member is null.";
            log.error(msg);
            throw new IllegalArgumentException(msg);
        }

        String clusterId = memberContext.getClusterId();
        Partition partition = memberContext.getPartition();

		log.debug("Received an instance spawn request : " + memberContext.toString());

        ComputeService computeService = null;
        Template template = null;

        if (partition == null) {
            String msg =
                         "Instance start-up failed. Specified Partition is null. " +
                                 memberContext.toString();
            log.error(msg);
            throw new IllegalArgumentException(msg);
        }

        String partitionId = partition.getId();
        ClusterContext ctxt = dataHolder.getClusterContext(clusterId);

        if (ctxt == null) {
            String msg = "Instance start-up failed. Invalid cluster id. " + memberContext.toString();
            log.error(msg);
            throw new IllegalArgumentException(msg);
        }

        String cartridgeType = ctxt.getCartridgeType();

        Cartridge cartridge = dataHolder.getCartridge(cartridgeType);

        if (cartridge == null) {
            String msg =
                         "Instance start-up failed. No matching Cartridge found [type] "+cartridgeType +". "+
                                 memberContext.toString();
            log.error(msg);
            throw new UnregisteredCartridgeException(msg);
        }

        memberContext.setCartridgeType(cartridgeType);


        IaasProvider iaasProvider = cartridge.getIaasProviderOfPartition(partitionId);
        if (iaasProvider == null) {
            String msg =
                         "Instance start-up failed. " + "There's no IaaS provided for the partition: " + partitionId +
                         " and for the Cartridge type: " + cartridgeType+". Only following "
                  		+ "partitions can be found in this Cartridge: "
                  		+cartridge.getPartitionToIaasProvider().keySet().toString()+ memberContext.toString() + ". ";
            log.fatal(msg);
            throw new InvalidIaasProviderException(msg);
        }
        String type = iaasProvider.getType();
        try {
            // generating the Unique member ID...
            String memberID = generateMemberId(clusterId);
            memberContext.setMemberId(memberID);
            // have to add memberID to the payload
            StringBuilder payload = new StringBuilder(ctxt.getPayload());
            addToPayload(payload, "MEMBER_ID", memberID);
            addToPayload(payload, "LB_CLUSTER_ID", memberContext.getLbClusterId());
            addToPayload(payload, "NETWORK_PARTITION_ID", memberContext.getNetworkPartitionId());
            addToPayload(payload, "PARTITION_ID", partitionId);

            Iaas iaas = iaasProvider.getIaas();
            if(ctxt.isVolumeRequired()){
                addToPayload(payload, "PERSISTENCE_MAPPING", getPersistencePayload(cartridge, iaas).toString());
            }
            
            if (log.isDebugEnabled()) {
                log.debug("Payload: " + payload.toString());
            }
            // reloading the payload with memberID
            iaasProvider.setPayload(payload.toString().getBytes());
            
            if (iaas == null) {
                if(log.isDebugEnabled()) {
                    log.debug("Iaas is null of Iaas Provider: "+type+". Trying to build IaaS...");
                }
                try {
                    iaas = CloudControllerUtil.getIaas(iaasProvider);
                } catch (InvalidIaasProviderException e) {
                    String msg ="Instance start up failed. "+memberContext.toString()+
                            "Unable to build Iaas of this IaasProvider [Provider] : " + type+". Cause: "+e.getMessage();
                    log.error(msg, e);
                    throw new InvalidIaasProviderException(msg, e);
                }
                
            }
            
            iaas.setDynamicPayload();
            // get the pre built ComputeService from provider or region or zone or host
            computeService = iaasProvider.getComputeService();
            template = iaasProvider.getTemplate();
                        
            if (template == null) {
                String msg =
                             "Failed to start an instance. " +
                                     memberContext.toString() +
                                     ". Reason : Jclouds Template is null for iaas provider [type]: "+iaasProvider.getType();
                log.error(msg);
                throw new InvalidIaasProviderException(msg);
            }

            // generate the group id from domain name and sub domain
            // name.
            // Should have lower-case ASCII letters, numbers, or dashes.
            // Should have a length between 3-15
            String str = clusterId.length() > 10 ? clusterId.substring(0, 10) : clusterId.substring(0, clusterId.length());
            String group = str.replaceAll("[^a-z0-9-]", "");
            
            if(ctxt.isVolumeRequired()) {
            	if (!ctxt.getListOfVolumes().isEmpty()) {
            		for (Volume volume : ctxt.getListOfVolumes()) {
						
            			if (volume.getId() == null) {
            				// create a new volume
            				createVolumeAndSetInClusterContext(volume, iaasProvider);
            			} 
					}
            	}
            }
            
            NodeMetadata node;

//            create and start a node
            Set<? extends NodeMetadata> nodes =
                                                computeService.createNodesInGroup(group, 1,
                                                                                  template);

            node = nodes.iterator().next();
            //Start allocating ip as a new job

            ThreadExecutor exec = ThreadExecutor.getInstance();
            exec.execute(new IpAllocator(memberContext, iaasProvider, cartridgeType, node));


            // node id
            String nodeId = node.getId();
            if (nodeId == null) {
                String msg = "Node id of the starting instance is null.\n" + memberContext.toString();
                log.fatal(msg);
                throw new IllegalStateException(msg);
            }
                memberContext.setNodeId(nodeId);
                if(log.isDebugEnabled()) {
                    log.debug("Node id was set. "+memberContext.toString());
                }
                
                // attach volumes
			if (ctxt.isVolumeRequired()) {
				// remove region prefix
				String instanceId = nodeId.indexOf('/') != -1 ? nodeId
						.substring(nodeId.indexOf('/') + 1, nodeId.length())
						: nodeId;
				memberContext.setInstanceId(instanceId);
				if (!ctxt.getListOfVolumes().isEmpty()) {
					for (Volume volume : ctxt.getListOfVolumes()) {
						try {
							iaas.attachVolume(instanceId, volume.getId(),
									volume.getDevice());
						} catch (Exception e) {
							// continue without throwing an exception, since
							// there is an instance already running
							log.error("Attaching Volume to Instance [ "
									+ instanceId + " ] failed!", e);
						}
					}
				}
			}

            log.info("Instance is successfully starting up. "+memberContext.toString());

            return memberContext;

        } catch (Exception e) {
            String msg = "Failed to start an instance. " + memberContext.toString()+" Cause: "+e.getMessage();
            log.error(msg, e);
            throw new IllegalStateException(msg, e);
        }

    }

	private void createVolumeAndSetInClusterContext(Volume volume,
			IaasProvider iaasProvider) {

		// iaas cannot be null at this state #startInstance method
		Iaas iaas = iaasProvider.getIaas();
		
		int sizeGB = volume.getSize();
		String volumeId = iaas.createVolume(sizeGB);
		volume.setId(volumeId);
		volume.setIaasType(iaasProvider.getType());
	}

	private StringBuilder getPersistencePayload(Cartridge cartridge, Iaas iaas) {
		StringBuilder persistencePayload = new StringBuilder();
		if(isPersistenceMappingAvailable(cartridge)){
			for(Volume volume : cartridge.getPersistence().getVolumes()){
				if(log.isDebugEnabled()){
					log.debug("Adding persistence mapping " + volume.toString());
				}
                if(persistencePayload.length() != 0) {
                   persistencePayload.append("|");
                }
				persistencePayload.append(iaas.getIaasDevice(volume.getDevice()));
				persistencePayload.append("|");
                persistencePayload.append(volume.getMappingPath());
			}
		}
        if(log.isDebugEnabled()){
            log.debug("Persistence payload is" + persistencePayload.toString());
        }
		return persistencePayload;
	}

	private boolean isPersistenceMappingAvailable(Cartridge cartridge) {
		return cartridge.getPersistence() != null && cartridge.getPersistence().isPersistanceRequired();
	}

	private void addToPayload(StringBuilder payload, String name, String value) {
	    payload.append(",");
        payload.append(name+"=" + value);
    }

    /**
	 * Persist data in registry.
	 */
	private void persist() {
		try {
			RegistryManager.getInstance().persist(
					dataHolder);
		} catch (RegistryException e) {

			String msg = "Failed to persist the Cloud Controller data in registry. Further, transaction roll back also failed.";
			log.fatal(msg);
			throw new CloudControllerException(msg, e);
		}
	}

    private String generateMemberId(String clusterId) {
        UUID memberId = UUID.randomUUID();
         return clusterId + memberId.toString();
    }

    @Override
    public void terminateInstance(String memberId) throws InvalidMemberException, InvalidCartridgeTypeException, 
    IllegalArgumentException{

        if(memberId == null) {
            String msg = "Termination failed. Null member id.";
            log.error(msg);
            throw new IllegalArgumentException(msg);
        }
        
        MemberContext ctxt = dataHolder.getMemberContextOfMemberId(memberId);
        
        if(ctxt == null) {
            String msg = "Termination failed. Invalid Member Id: "+memberId;
            log.error(msg);
            throw new InvalidMemberException(msg);
        }
        
        ThreadExecutor exec = ThreadExecutor.getInstance();
        exec.execute(new InstanceTerminator(ctxt));

	}
    
    private class InstanceTerminator implements Runnable {

        private MemberContext ctxt;

        public InstanceTerminator(MemberContext ctxt) {
            this.ctxt = ctxt;
        }

        @Override
        public void run() {

            String memberId = ctxt.getMemberId();
            String clusterId = ctxt.getClusterId();
            String partitionId = ctxt.getPartition().getId();
            String cartridgeType = ctxt.getCartridgeType();
            String nodeId = ctxt.getNodeId();

            try {
                // these will never be null, since we do not add null values for these.
                Cartridge cartridge = dataHolder.getCartridge(cartridgeType);

                log.info("Starting to terminate an instance with member id : " + memberId +
                         " in partition id: " + partitionId + " of cluster id: " + clusterId +
                         " and of cartridge type: " + cartridgeType);

                if (cartridge == null) {
                    String msg =
                                 "Termination of Member Id: " + memberId + " failed. " +
                                         "Cannot find a matching Cartridge for type: " +
                                         cartridgeType;
                    log.error(msg);
                    throw new InvalidCartridgeTypeException(msg);
                }

                // if no matching node id can be found.
                if (nodeId == null) {

                    String msg =
                                 "Termination failed. Cannot find a node id for Member Id: " +
                                         memberId;
                    log.error(msg);
                    throw new InvalidMemberException(msg);
                }

                IaasProvider iaasProvider = cartridge.getIaasProviderOfPartition(partitionId);

                // terminate it!
                terminate(iaasProvider, nodeId, ctxt);

                // log information
                logTermination(ctxt);

            } catch (Exception e) {
                String msg =
                             "Instance termination failed. "+ctxt.toString();
                log.error(msg, e);
                throw new CloudControllerException(msg, e);
            }

        }
    }

    private class IpAllocator implements Runnable {

        private MemberContext memberContext;
        private IaasProvider iaasProvider;
        private String cartridgeType;
        NodeMetadata node;

        public IpAllocator(MemberContext memberContext, IaasProvider iaasProvider, 
        		String cartridgeType, NodeMetadata node) {
            this.memberContext = memberContext;
            this.iaasProvider = iaasProvider;
            this.cartridgeType = cartridgeType;
            this.node = node;
        }

        @Override
        public void run() {


            String clusterId = memberContext.getClusterId();
            Partition partition = memberContext.getPartition();
            String publicIp = null;

            try{

                String autoAssignIpProp =
                                          iaasProvider.getProperty(CloudControllerConstants.AUTO_ASSIGN_IP_PROPERTY);

                    // reset ip
                    String ip = "";
                    // default behavior is autoIpAssign=false
                    if (autoAssignIpProp == null ||
                        (autoAssignIpProp != null && autoAssignIpProp.equals("false"))) {

                        Iaas iaas = iaasProvider.getIaas();
                        // allocate an IP address - manual IP assigning mode
                        ip = iaas.associateAddress(node);
                        
						if (ip != null) {
							memberContext.setAllocatedIpAddress(ip);
							log.info("Allocated an ip address: "
									+ memberContext.toString());
						}
                    }

                    // public ip
                    if (node.getPublicAddresses() != null &&
                        node.getPublicAddresses().iterator().hasNext()) {
                        ip = node.getPublicAddresses().iterator().next();
                        publicIp = ip;
                        memberContext.setPublicIpAddress(ip);
                        log.info("Retrieving Public IP Address : " + memberContext.toString());
                    }

                    // private IP
                    if (node.getPrivateAddresses() != null &&
                        node.getPrivateAddresses().iterator().hasNext()) {
                        ip = node.getPrivateAddresses().iterator().next();
                        memberContext.setPrivateIpAddress(ip);
                        log.info("Retrieving Private IP Address. " + memberContext.toString());
                    }

                    dataHolder.addMemberContext(memberContext);

                    // persist in registry
                    persist();

                    String memberID = memberContext.getMemberId();

                    // trigger topology
                    TopologyBuilder.handleMemberSpawned(memberID, cartridgeType, clusterId, memberContext.getNetworkPartitionId(),
                            partition.getId(), ip, memberContext.getLbClusterId(),publicIp);

                    // update the topology with the newly spawned member
                    // publish data
                    CartridgeInstanceDataPublisher.publish(memberID,
                                                        memberContext.getPartition().getId(),
                                                        memberContext.getNetworkPartitionId(),
                                                        memberContext.getClusterId(),
                                                        cartridgeType,
                                                        MemberStatus.Created.toString(),
                                                        node);
                    if (log.isDebugEnabled()) {
                        log.debug("Node details: \n" + node.toString());
                    }

            } catch (Exception e) {
                String msg = "Error occurred while allocating an ip address. " + memberContext.toString();
                log.error(msg, e);
                throw new CloudControllerException(msg, e);
            }


        }
    }

	@Override
	public void terminateAllInstances(String clusterId) throws IllegalArgumentException, InvalidClusterException {

		log.info("Starting to terminate all instances of cluster : "
				+ clusterId);
		
		if(clusterId == null) {
		    String msg = "Instance termination failed. Cluster id is null.";
		    log.error(msg);
		    throw new IllegalArgumentException(msg);
		}
		
		List<MemberContext> ctxts = dataHolder.getMemberContextsOfClusterId(clusterId);
		
		if(ctxts == null) {
		    String msg = "Instance termination failed. No members found for cluster id: "+clusterId;
		    log.warn(msg);
            return;
		}
		
		ThreadExecutor exec = ThreadExecutor.getInstance();
		for (MemberContext memberContext : ctxts) {
            exec.execute(new InstanceTerminator(memberContext));
        }

	}


	/**
	 * A helper method to terminate an instance.
     * @param iaasProvider
     * @param ctxt
     * @param nodeId
     * @return will return the IaaSProvider
     */
	private IaasProvider terminate(IaasProvider iaasProvider, 
			String nodeId, MemberContext ctxt) {
	    Iaas iaas = iaasProvider.getIaas();
	    if (iaas == null) {
	        
	        try {
	            iaas = CloudControllerUtil.getIaas(iaasProvider);
	        } catch (InvalidIaasProviderException e) {
	            String msg =
	                    "Instance termination failed. " +ctxt.toString()  +
	                    ". Cause: Unable to build Iaas of this " + iaasProvider.toString();
	            log.error(msg, e);
	            throw new CloudControllerException(msg, e);
	        }
	        
	    }
	    
	    //detach volumes if any
	    detachVolume(iaasProvider, ctxt);
	    
		// destroy the node
		iaasProvider.getComputeService().destroyNode(nodeId);

		// release allocated IP address
		if (ctxt.getAllocatedIpAddress() != null) {
            iaas.releaseAddress(ctxt.getAllocatedIpAddress());
		}
		
		log.info("Member is terminated: "+ctxt.toString());
		return iaasProvider;
	}

	private void detachVolume(IaasProvider iaasProvider, MemberContext ctxt) {
		String clusterId = ctxt.getClusterId();
		ClusterContext clusterCtxt = dataHolder.getClusterContext(clusterId);
		if (clusterCtxt.getListOfVolumes() != null) {
			for (Volume volume : clusterCtxt.getListOfVolumes()) {
				
				try {
					String volumeId = volume.getId();
					if (volumeId == null) {
						return;
					}
					Iaas iaas = iaasProvider.getIaas();
					iaas.detachVolume(ctxt.getInstanceId(), volumeId);
				} catch (ResourceNotFoundException ignore) {
					if(log.isDebugEnabled()) {
						log.debug(ignore);
					}
				}
			}
		}
	}

	private void logTermination(MemberContext memberContext) {

        //updating the topology
        TopologyBuilder.handleMemberTerminated(memberContext.getCartridgeType(), 
        		memberContext.getClusterId(), memberContext.getNetworkPartitionId(), 
        		memberContext.getPartition().getId(), memberContext.getMemberId());

        //publishing data
        CartridgeInstanceDataPublisher.publish(memberContext.getMemberId(),
                                                        memberContext.getPartition().getId(),
                                                        memberContext.getNetworkPartitionId(),
                                                        memberContext.getClusterId(),
                                                        memberContext.getCartridgeType(),
                                                        MemberStatus.Terminated.toString(),
                                                        null);

        // update data holders
        dataHolder.removeMemberContext(memberContext.getMemberId(), memberContext.getClusterId());
        
		// persist
		persist();

	}

	@Override
	public boolean registerService(Registrant registrant)
			throws UnregisteredCartridgeException, IllegalArgumentException {

	    String cartridgeType = registrant.getCartridgeType();
	    String clusterId = registrant.getClusterId();
        String payload = registrant.getPayload();
        String hostName = registrant.getHostName();
        
        if(cartridgeType == null || clusterId == null || payload == null || hostName == null) {
	        String msg = "Null Argument/s detected: Cartridge type: "+cartridgeType+", " +
	                "Cluster Id: "+clusterId+", Payload: "+payload+", Host name: "+hostName;
	        log.error(msg);
	        throw new IllegalArgumentException(msg);
	    }
	    
        Cartridge cartridge = null;
        if ((cartridge = dataHolder.getCartridge(cartridgeType)) == null) {

            String msg = "Registration of cluster: "+clusterId+
                    " failed. - Unregistered Cartridge type: " + cartridgeType;
            log.error(msg);
            throw new UnregisteredCartridgeException(msg);
        }
        
        Properties props = CloudControllerUtil.toJavaUtilProperties(registrant.getProperties());
        String property = props.getProperty(Constants.IS_LOAD_BALANCER);
        boolean isLb = property != null ? Boolean.parseBoolean(property) : false;

        ClusterContext ctxt = buildClusterContext(cartridge, clusterId,
				payload, hostName, props, isLb);
	    
		dataHolder.addClusterContext(ctxt);
	    TopologyBuilder.handleClusterCreated(registrant, isLb);
	    
	    persist();
	    
		return true;
	}

	private ClusterContext buildClusterContext(Cartridge cartridge,
			String clusterId, String payload, String hostName,
			Properties props, boolean isLb) {
		
		// initialize ClusterContext
		ClusterContext ctxt = new ClusterContext(clusterId, cartridge.getType(), payload, 
				hostName, isLb);
		
		String property;
		property = props.getProperty(Constants.GRACEFUL_SHUTDOWN_TIMEOUT);
		long timeout = property != null ? Long.parseLong(property) : 30000;
		
		property = props.getProperty(Constants.IS_VOLUME_REQUIRED);
        boolean isVolumeRequired = property != null ? Boolean.parseBoolean(property) : false;
        
        if(isVolumeRequired) {
        	Persistence persistenceData = cartridge.getPersistence();
        	
        	if(persistenceData != null) {
        		Volume[] volumes = persistenceData.getVolumes();
        		
        		property = props.getProperty(Constants.SHOULD_DELETE_VOLUME);
        		property = props.getProperty(Constants.VOLUME_SIZE);
        		
        		for (Volume volume : volumes) {
        			int volumeSize = property != null ? Integer.parseInt(property) : volume.getSize();
        			boolean shouldDeleteVolume = property != null ? Boolean.parseBoolean(property) : volume.isRemoveOntermination();
        			
        			Volume v = new Volume();
        			v.setSize(volumeSize);
        			v.setRemoveOntermination(shouldDeleteVolume);
        			v.setDevice(volume.getDevice());
        			v.setMappingPath(volume.getMappingPath());
        			ctxt.addVolume(v);
					
				}
        	} else {
        		// if we cannot find necessary data, we would not consider 
        		// this as a volume required instance.
        		isVolumeRequired = false;
        	}
        	
        	ctxt.setVolumeRequired(isVolumeRequired);
        }
        
	    ctxt.setTimeoutInMillis(timeout);
		return ctxt;
	}

	@Override
	public String[] getRegisteredCartridges() {
		// get the list of cartridges registered
		List<Cartridge> cartridges = dataHolder
				.getCartridges();

		if (cartridges == null) {
			return new String[0];
		}

		String[] cartridgeTypes = new String[cartridges.size()];
		int i = 0;

		for (Cartridge cartridge : cartridges) {
			cartridgeTypes[i] = cartridge.getType();
			i++;
		}

		return cartridgeTypes;
	}

	@Override
	public CartridgeInfo getCartridgeInfo(String cartridgeType)
			throws UnregisteredCartridgeException {
		Cartridge cartridge = dataHolder
				.getCartridge(cartridgeType);

		if (cartridge != null) {

			return CloudControllerUtil.toCartridgeInfo(cartridge);

		}

		String msg = "Cannot find a Cartridge having a type of "
				+ cartridgeType + ". Hence unable to find information.";
		log.error(msg);
		throw new UnregisteredCartridgeException(msg);
	}

    @Override
	public void unregisterService(String clusterId) throws UnregisteredClusterException {
        final String clusterId_ = clusterId;

        Runnable terminateInTimeout = new Runnable() {
            @Override
            public void run() {
                ClusterContext ctxt = dataHolder.getClusterContext(clusterId_);
                 if(ctxt == null) {
                     String msg = "Unregistration of service cluster failed. Cluster not found: " + clusterId_;
                     log.error(msg);
                 }
                 Collection<Member> members = TopologyManager.getTopology().
                         getService(ctxt.getCartridgeType()).getCluster(clusterId_).getMembers();
                 //finding the responding members from the existing members in the topology.
                int sizeOfRespondingMembers = 0;
                for(Member member : members) {
                    if(member.getStatus().getCode() >= MemberStatus.Activated.getCode()) {
                        sizeOfRespondingMembers ++;
                    }
                }

                long endTime = System.currentTimeMillis() + ctxt.getTimeoutInMillis() * sizeOfRespondingMembers;
                while(System.currentTimeMillis()< endTime) {
                    CloudControllerUtil.sleep(1000);

                }

                 // if there're still alive members
                 if(members.size() > 0) {
                     //forcefully terminate them
                     for (Member member : members) {

                         try {
                            terminateInstance(member.getMemberId());
                        } catch (Exception e) {
                            // we are not gonna stop the execution due to errors.
                            log.warn("Instance termination failed of member [id] " + member.getMemberId(), e);
                        }
                    }
                 }
            }
        };
        Runnable unregister = new Runnable() {
             public void run() {
                 ClusterContext ctxt = dataHolder.getClusterContext(clusterId_);
                 if(ctxt == null) {
                     String msg = "Unregistration of service cluster failed. Cluster not found: " + clusterId_;
                     log.error(msg);
                 }
                 Collection<Member> members = TopologyManager.getTopology().
                         getService(ctxt.getCartridgeType()).getCluster(clusterId_).getMembers();
                 long endTime = System.currentTimeMillis() + ctxt.getTimeoutInMillis() * members.size();

                 while(members.size() > 0) {
                    //waiting until all the members got removed from the Topology/ timed out
                    CloudControllerUtil.sleep(1000);
                 }

                 log.info("Unregistration of service cluster: " + clusterId_);
                 deleteVolumes(ctxt);
                 TopologyBuilder.handleClusterRemoved(ctxt);
                 dataHolder.removeClusterContext(clusterId_);
                 dataHolder.removeMemberContextsOfCluster(clusterId_);
                 persist();
             }

            private void deleteVolumes(ClusterContext ctxt) {
                if(ctxt.isVolumeRequired()) {
                     Cartridge cartridge = dataHolder.getCartridge(ctxt.getCartridgeType());
                     if(cartridge != null && cartridge.getIaases() != null && !ctxt.getListOfVolumes().isEmpty()) {
                         for (Volume volume : ctxt.getListOfVolumes()) {
                            if(volume.getId() != null) {
                                String iaasType = volume.getIaasType();
                                Iaas iaas = dataHolder.getIaasProvider(iaasType).getIaas();
                                if(iaas != null) {
                                    try {
                                    // delete the volume
                                    iaas.deleteVolume(volume.getId());
                                    } catch(Exception ignore) {
                                        if(log.isDebugEnabled()) {
                                            log.debug(ignore);
                                        }
                                    }
                                }
                            }
                        }

                     }
                 }
            }
        };
        new Thread(terminateInTimeout).start();
        new Thread(unregister).start();
        
	}

		

    @Override
    public boolean validateDeploymentPolicy(String cartridgeType, Partition[] partitions) 
            throws InvalidPartitionException, InvalidCartridgeTypeException {

        Map<String, IaasProvider> partitionToIaasProviders =
                                                             new ConcurrentHashMap<String, IaasProvider>();

        Cartridge cartridge = dataHolder.getCartridge(cartridgeType);

        if (cartridge == null) {
            String msg = "Invalid Cartridge Type: " + cartridgeType;
            log.error(msg);
            throw new InvalidCartridgeTypeException(msg);
        }

        for (Partition partition : partitions) {
            String provider = partition.getProvider();
            IaasProvider iaasProvider = cartridge.getIaasProvider(provider);

            if (iaasProvider == null) {
                String msg =
                             "Invalid Partition - " + partition.toString() +
                                     ". Cause: Iaas Provider is null for Provider: " + provider;
                log.error(msg);
                throw new InvalidPartitionException(msg);
            }

            Iaas iaas = iaasProvider.getIaas();
            
            if (iaas == null) {
                
                try {
                    iaas = CloudControllerUtil.getIaas(iaasProvider);
                } catch (InvalidIaasProviderException e) {
                    String msg =
                            "Invalid Partition - " + partition.toString() +
                            ". Cause: Unable to build Iaas of this IaasProvider [Provider] : " + provider+". "+e.getMessage();
                    log.error(msg, e);
                    throw new InvalidPartitionException(msg, e);
                }
                
            }
            
            PartitionValidator validator = iaas.getPartitionValidator();
            validator.setIaasProvider(iaasProvider);
            IaasProvider updatedIaasProvider =
                                               validator.validate(partition.getId(),
                                                                  CloudControllerUtil.toJavaUtilProperties(partition.getProperties()));
            // add to a temporary Map
            partitionToIaasProviders.put(partition.getId(), updatedIaasProvider);
            
            if (log.isDebugEnabled()) {
            	log.debug("Partition "+partition.toString()+ " is validated successfully "
            			+ "against the Cartridge: "+cartridgeType);
            }

        }

        // if and only if the deployment policy valid
        cartridge.addIaasProviders(partitionToIaasProviders);
        
        // persist data
        persist();
        
        log.info("All partitions "+CloudControllerUtil.getPartitionIds(partitions)+
        		" were validated successfully, against the Cartridge: "+cartridgeType);
        
        return true;
    }

    @Override
    public boolean validatePartition(Partition partition) throws InvalidPartitionException {
    	//FIXME add logs
        String provider = partition.getProvider();
        IaasProvider iaasProvider = dataHolder.getIaasProvider(provider);

        if (iaasProvider == null) {
            String msg =
                         "Invalid Partition - " + partition.toString()+". Cause: Iaas Provider " +
                                 "is null for Partition Provider: "+provider;
            log.error(msg);
            throw new InvalidPartitionException(msg);
        }
        
        Iaas iaas = iaasProvider.getIaas();
        
        if (iaas == null) {
            
        	try {
                iaas = CloudControllerUtil.getIaas(iaasProvider);
            } catch (InvalidIaasProviderException e) {
                String msg =
                        "Invalid Partition - " + partition.toString() +
                        ". Cause: Unable to build Iaas of this IaasProvider [Provider] : " + provider+". "+e.getMessage();
                log.error(msg, e);
                throw new InvalidPartitionException(msg, e);
            }
            
        }

        PartitionValidator validator = iaas.getPartitionValidator();
        validator.setIaasProvider(iaasProvider);
        validator.validate(partition.getId(),
                           CloudControllerUtil.toJavaUtilProperties(partition.getProperties()));
        
        return true;
    }

}

