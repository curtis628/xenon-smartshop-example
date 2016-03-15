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

   public static class Arguments {
      public String dnshost;
      public int dnsport;
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

      // The key to the map is the node group URI; and the value is the node group selector URI
      Map<String, String> nodeGroupToSelectorMap = new HashMap<>();
      nodeGroupToSelectorMap.put(PRODUCT_NODE_GROUP_URI, PRODUCT_NODE_SELECTOR_URI);
      createNodeGroups(nodeGroupToSelectorMap.keySet());
      createNodeSelectors(nodeGroupToSelectorMap);

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
   private void createNodeGroups(Set<String> nodeGroupUris) throws Throwable {
      if (nodeGroupUris == null || nodeGroupUris.size() == 0) {
         throw new IllegalArgumentException("Must provide at least one node group URI");
      }

      Collection<Operation> nodeGroupOperations = new ArrayList<>(nodeGroupUris.size());
      for (String nodeGroupUri : nodeGroupUris) {
         nodeGroupOperations.add(NodeGroupFactoryService
               .createNodeGroupPostOp(this, nodeGroupUri)
               .setReferer(UriUtils.buildUri(this, "")));
      }

      sendOperationsSynchronously(nodeGroupOperations);
      this.log(Level.INFO, "Created node groups successfully: %s", nodeGroupUris);
   }

   private void createNodeSelectors(Map<String, String> nodeGroupToSelectorMap) throws Throwable {
      if (nodeGroupToSelectorMap == null || nodeGroupToSelectorMap.size() == 0) {
         throw new IllegalArgumentException("Must provide a map with at least one node group to node selector entry");
      }

      List<Operation> nodeSelectorPosts = new ArrayList<>(nodeGroupToSelectorMap.size());
      List<Service> nodeSelectorServices = new ArrayList<>(nodeGroupToSelectorMap.size());
      for (String nodeGroup : nodeGroupToSelectorMap.keySet()) {
         String nodeSelectorUri = nodeGroupToSelectorMap.get(nodeGroup);

         Operation postNodeSelector = Operation.createPost(
               UriUtils.buildUri(this, nodeSelectorUri))
               .setReferer(UriUtils.buildUri(this, ""));

         NodeSelectorService nodeSelectorService = new ConsistentHashingNodeSelectorService();
         NodeSelectorState nodeSelectorState = new NodeSelectorState();
         nodeSelectorState.nodeGroupLink = nodeGroup;
         postNodeSelector.setBody(nodeSelectorState);

         nodeSelectorPosts.add(postNodeSelector);
         nodeSelectorServices.add(nodeSelectorService);
      }

      startServicesSynchronously(nodeSelectorPosts, nodeSelectorServices);
      this.log(Level.INFO, "Created node selectors successfully: %s", nodeGroupToSelectorMap.values());
   }

   protected void startServicesSynchronously(List<Operation> startPosts,
         List<Service> services)
         throws Throwable {
      CountDownLatch l = new CountDownLatch(services.size());
      Throwable[] failure = new Throwable[1];
      StringBuilder sb = new StringBuilder();

      Operation.CompletionHandler h = (o, e) -> {
         try {
            if (e != null) {
               failure[0] = e;
               log(Level.SEVERE, "Service %s failed start: %s", o.getUri(), e);
               return;
            }

            log(Level.FINE, "started %s", o.getUri().getPath());
         } finally {
            l.countDown();
         }
      };
      int index = 0;


      for (Service s : services) {
         Operation startPost = startPosts.get(index++);
         startPost.setCompletion(h);
         sb.append(startPost.getUri().toString()).append(Operation.CR_LF);
         log(Level.FINE, "starting %s", startPost.getUri());
         startService(startPost, s);
      }

      if (!l.await(this.getState().operationTimeoutMicros, TimeUnit.MICROSECONDS)) {
         log(Level.SEVERE, "One of the core services failed start: %s",
               sb.toString(),
               new TimeoutException());
      }

      if (failure[0] != null) {
         throw failure[0];
      }
   }

   private void sendOperationsSynchronously(Collection<Operation> operations) throws Throwable {
      if (operations == null || operations.size() == 0) {
         throw new IllegalArgumentException("Must provide at least one operation");
      }
      log(Level.FINE, "sendOperationsSynchronously(). [operations=%s]", operations);

      Throwable[] error = new Throwable[1];
      CountDownLatch c = new CountDownLatch(operations.size());
      Operation.CompletionHandler comp = (o, e) -> {
         this.log(Level.FINE, "Completion handler running: [o=%s] [e=%s]", o, e);

         if (e != null) {
            error[0] = e;
            log(Level.SEVERE, "Error executing operation in sequence. [op=%s] [error=%s]", o, e);
            stop();
            c.countDown();
            return;
         }
         log(Level.FINE, "POST successful to: %s", o.getUri().getPath());
         c.countDown();
      };

      for (Operation operation : operations) {
         operation.setCompletion(comp);
      }

      OperationSequence parallelSequence = OperationSequence.create(operations.toArray(new Operation[0]));

      log(Level.FINE, "Sending sequence...");
      parallelSequence.sendWith(this);

      if (!c.await(getState().operationTimeoutMicros, TimeUnit.MICROSECONDS)) {
         throw new TimeoutException();
      }
      if (error[0] != null) {
         throw error[0];
      }

      log(Level.FINE, "Successfully processed %d operations", operations.size());
   }

}
