package com.tcurt628.smartshop.product;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.services.common.RootNamespaceService;

import java.util.logging.Level;

/**
 * Our entry point, spawning a host that run/showcase examples we can play with.
 */
public class ProductHost extends ServiceHost {

   public static void main(String[] args) throws Throwable {
      ProductHost h = new ProductHost();
      h.initialize(args);
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

      // start our service
      super.startService(
            Operation.createPost(UriUtils.buildFactoryUri(this, ProductService.class)),
            ProductService.createFactory());

      return this;
   }
}
