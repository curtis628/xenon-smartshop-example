package com.tcurt628.smartshop.product;

import com.vmware.xenon.common.CommandLineArgumentParser;
import com.vmware.xenon.common.NodeSelectorService;
import com.vmware.xenon.common.NodeSelectorState;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.OperationJoin;
import com.vmware.xenon.common.OperationSequence;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.dns.services.DNSFactoryService;
import com.vmware.xenon.services.common.ConsistentHashingNodeSelectorService;
import com.vmware.xenon.services.common.NodeGroupFactoryService;
import com.vmware.xenon.services.common.NodeGroupService;
import com.vmware.xenon.services.common.RootNamespaceService;
import com.vmware.xenon.services.common.ServiceUriPaths;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Our entry point, spawning a host that run/showcase examples we can play with.
 */
public class ProductHost extends ServiceHost {

   private static final String PRODUCT_NODE_GROUP_NAME = "product";
   private static final String PRODUCT_NODE_SELECTOR_NAME = "product";

   public static final String PRODUCT_NODE_GROUP_URI = ServiceUriPaths.NODE_GROUP_FACTORY + "/" + PRODUCT_NODE_GROUP_NAME;
   public static final String PRODUCT_NODE_SELECTOR_URI = ServiceUriPaths.NODE_SELECTOR_PREFIX + "/" + PRODUCT_NODE_SELECTOR_NAME;

   /** The key to the map is the node selector URI; and the value is the node group URI */
   private static Map<String, String> NODE_GROUP_TO_SELECTORS_MAP = new HashMap<>();
   static {
      NODE_GROUP_TO_SELECTORS_MAP.put(PRODUCT_NODE_SELECTOR_URI, PRODUCT_NODE_GROUP_URI);
   }

   public static class Arguments {
      public String dnshost;
      public int dnsport;
      public int initialNodes = 1;
   }

   public static Arguments hostArguments = new Arguments();

   public static void main(String[] args) throws Throwable {
      CommandLineArgumentParser.parse(hostArguments, args);
      ProductHost h = new ProductHost();
      h.initialize(args);
      h.toggleDebuggingMode(true);
      h.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         h.log(Level.WARNING, "Host stopping ...");
         h.stop();
         h.log(Level.WARNING, "Host is stopped");
      }));
   }

   /** Start services: a host can run multiple services. */
   @Override
   public ServiceHost start() throws Throwable {
      super.start();

      // Start core services (logging, gossiping)-- must be done once
      startDefaultCoreServicesSynchronously();

      // Start the root namespace service: this will list all available factory services for
      // queries to the root (/)
      super.startService(
            Operation.createPost(UriUtils.buildUri(this, RootNamespaceService.class)),
            new RootNamespaceService());

      this.log(Level.FINE, "Default core services started!");

      // Create custom node groups and selectors
      createNodeGroupsAndSelectors(NODE_GROUP_TO_SELECTORS_MAP);

      // start our service
      super.startService(
            Operation.createPost(UriUtils.buildFactoryUri(this, ProductService.class)),
            ProductService.createFactory());

      // Regiser our service with DNS
      registerWithDNS();
      return this;
   }


   private void registerWithDNS() {
      URI dnsHost = UriUtils.buildUri(hostArguments.dnshost, hostArguments.dnsport, null, null);

      Operation.CompletionHandler completionHandler = (o, e) -> {
         assert (e == null);
         this.log(Level.INFO, "Successfully registered with DNS at " + dnsHost);
      };

      this.sendRequest(DNSFactoryService.createPost(dnsHost
            ,this,
            ProductService.FACTORY_LINK,
            ProductService.class.getSimpleName(),
            null,
            ProductService.FACTORY_LINK + "/available",
            1L).setCompletion(completionHandler));
   }

   //
   // TODO Extract all code below here to the base ServiceHost class.
   //
   private void createNodeGroupsAndSelectors(Map<String, String> nodeSelectorToNodeGroupMap) throws Throwable {
      if (nodeSelectorToNodeGroupMap == null || nodeSelectorToNodeGroupMap.size() == 0) {
         throw new IllegalArgumentException("Must provide a map with at least one node group to node selector entry");
      }

      Set<String> nodeGroupUris = new HashSet<>(nodeSelectorToNodeGroupMap.values());
      log(Level.INFO, "Creating node groups: %s", nodeGroupUris);

      Collection<Operation> createNodeGroups = new ArrayList<>(nodeGroupUris.size());
      Collection<Operation> patchQuorums = new ArrayList<>(nodeGroupUris.size());
      List<Operation> createNodeSelectors = new ArrayList<>(nodeSelectorToNodeGroupMap.size());
      List<Service> createNodeSelectorServices = new ArrayList<>(nodeSelectorToNodeGroupMap.size());
      for (String nodeGroupSelector : nodeSelectorToNodeGroupMap.keySet()) {
         String nodeGroup = nodeSelectorToNodeGroupMap.get(nodeGroupSelector);
         Operation postNewNodeGroup = createNewNodeGroupOperation(nodeGroup);
         createNodeGroups.add(postNewNodeGroup);

         Operation patchUpdateQuorum = createQuorumRequestOperation(nodeGroup);
         patchQuorums.add(patchUpdateQuorum);

         NodeSelectorService nodeSelectorService = new ConsistentHashingNodeSelectorService();
         NodeSelectorState nodeSelectorState = new NodeSelectorState();
         nodeSelectorState.nodeGroupLink = nodeGroup;

         Operation postNodeSelector = Operation.createPost(
               UriUtils.buildUri(this, nodeGroupSelector))
               .setReferer(UriUtils.buildUri(this, ""));
         postNodeSelector.setBody(nodeSelectorState);
         createNodeSelectors.add(postNodeSelector);
         createNodeSelectorServices.add(nodeSelectorService);
      }

      OperationJoin nodeGroupOperations = OperationJoin.create(createNodeGroups);
      OperationJoin quorumOperations = OperationJoin.create(patchQuorums);

      OperationSequence.create(nodeGroupOperations, quorumOperations)
            .setCompletion( (ops,failures) -> {
               if (failures != null && !failures.isEmpty()) {
                  log(Level.SEVERE, "Error when creating node groups/selectors: %s. [ops.values=%s] [failures.values=%s]",
                        nodeGroupUris, ops.values(), failures.values());
                  throw new IllegalStateException("Error when creating node groups/selectors", failures.values().iterator().next());
               }

               log(Level.INFO, "Successfully created node-groups: %s", nodeGroupUris);
               log(Level.FINE, "Completed %d operations: %s", ops.size(), ops.values());
               for (int ndx=0; ndx < createNodeSelectors.size(); ndx++) {
                  Operation nodeSelectorPost = createNodeSelectors.get(ndx);
                  Service nodeSelectorService = createNodeSelectorServices.get(ndx);
                  startService(nodeSelectorPost, nodeSelectorService);
                  log(Level.FINE, "Started [nodeSelector=%s]", nodeSelectorPost.getUri());
               }
            })
            .sendWith(this);
      this.log(Level.FINE, "Sent requests to create node groups: %s", nodeGroupUris);
   }

   private Operation createNewNodeGroupOperation(String nodeGroup) {
      Operation postNewNodeGroup = NodeGroupFactoryService
            .createNodeGroupPostOp(this, nodeGroup)
            .setReferer(getUri());
      return postNewNodeGroup;
   }

   private Operation createQuorumRequestOperation(String nodeGroup) {
      NodeGroupService.UpdateQuorumRequest quorumRequest = NodeGroupService.UpdateQuorumRequest.create(true);
      quorumRequest.setMembershipQuorum(hostArguments.initialNodes);
      URI nodeGroupUri = UriUtils.buildUri(this, nodeGroup);
      Operation patchUpdateQuorum = Operation.createPatch(nodeGroupUri)
            .setBody(quorumRequest)
            .setReferer(getUri());
      return patchUpdateQuorum;
   }

}
