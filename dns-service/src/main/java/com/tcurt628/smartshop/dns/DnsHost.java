package com.tcurt628.smartshop.dns;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.dns.services.DNSServices;
import com.vmware.xenon.services.common.RootNamespaceService;
import com.vmware.xenon.ui.UiService;

import java.util.logging.Level;

public class DnsHost extends ServiceHost {

   public static void main(String[] args) throws Throwable {
      DnsHost h = new DnsHost();
      h.initialize(args);
      h.toggleDebuggingMode(true);
      h.start();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
         h.log(Level.WARNING, "Host stopping ...");
         h.stop();
         h.log(Level.WARNING, "Host is stopped");
      }));
   }

   @Override
   public ServiceHost start() throws Throwable {
      super.start();

      startDefaultCoreServicesSynchronously();

      setAuthorizationContext(this.getSystemAuthorizationContext());

      super.startService(
            Operation.createPost(UriUtils.buildUri(this, RootNamespaceService.class)),
            new RootNamespaceService()
      );

      // Start UI service
      super.startService(
            Operation.createPost(UriUtils.buildUri(this, UiService.class)),
            new UiService());

      DNSServices.startServices(this, null);

      setAuthorizationContext(null);

      return this;
   }
}
