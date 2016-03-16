package com.tcurt628.smartshop.dns;

import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.ServiceHost;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.dns.services.DNSService;
import com.vmware.xenon.services.common.ServiceUriPaths;
import org.apache.commons.lang3.builder.ToStringBuilder;

import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SmartShopDnsQueries {

   /** Not crazy about this, but it works for now I guess... */
   private static Logger logger = Logger.getLogger(SmartShopDnsQueries.class.getName());
   static {
      logger.setLevel(Level.FINE);
   }

   private String ip;
   private int port;

   public SmartShopDnsQueries(String ip, int port) {
      this.ip = ip;
      this.port = port;
   }

   /**
    * This methods makes calling an service endpoint (of which, you don't know the host that's running it) pretty
    * straightforward.
    *
    * @param dnsFilter The DNS query filter to use to find the host that's running a particular service. For example, if
    *                  you are trying to call the {@code /products} endpoint, you might pass the following for this
    *                  parameter: {@code "$filter=serviceLink eq '/products'"}
    * @param queryUri  This is the full path of the query you want to execute, which should be added to the host and
    *                  port from the DNS result found from using {@code dnsFilter}. Example: {@code "/products/123456"}
    * @param referer   This is the host that should be sending the requests
    * @param handler   This is the handler that will be executed on the client's behalf after successfully finding the
    *                  full URI to use from DNS
    */
   public void queryDns(String dnsFilter, String queryUri, ServiceHost referer, Operation.CompletionHandler handler) {
      URI dnsLookupUri = UriUtils.buildUri(ip, port, ServiceUriPaths.DNS + "/query", dnsFilter);

      Operation dnsLookupOperation = Operation.createGet(dnsLookupUri)
            .setReferer(referer.getUri())
            .setCompletion(((o, e) -> {
               if(e != null) {
                  String message = String.format("DNS Lookup Error for query: %s", o.getUri());
                  logger.log(Level.SEVERE, message, e);
                  throw new IllegalStateException(message, e);
               }

               ServiceDocumentQueryResult result = o.getBody(ServiceDocumentQueryResult.class);
               if(result.documentLinks == null || result.documentLinks.size() <= 0) {
                  String message = String.format("DNS Lookup Query returned no results: %s", o.getUri());
                  logger.log(Level.WARNING, message);
                  throw new IllegalStateException(message, e);
               }

               String documentKey = result.documentLinks.get(0);
               logger.log(Level.FINE, String.format("DNS service lookup returned %d records. Using: [documentKey=%s]", result.documentCount, documentKey));
               Object documentValue = result.documents.get(documentKey);
               DNSService.DNSServiceState serviceState = Utils.fromJson(documentValue, DNSService.DNSServiceState.class);
               logger.log(Level.FINE, String.format("Retrieved [dnsServiceState=%s]", ToStringBuilder.reflectionToString(serviceState)));

               if(serviceState.nodeReferences.size() > 0) {
                  URI dnsResponse = UriUtils.extendUri(serviceState.nodeReferences.iterator().next(), queryUri);
                  logger.log(Level.INFO, String.format("DNS Response for query: %s", dnsResponse));

                  // Add "no queuing" directive because, by default, it will wait on the service for it to be created
                  // NO_QUEUING says return immediately, even if the service doesn't exist yet (a HTTP 404)
                  Operation getDirect = Operation.createGet(dnsResponse)
                        .setReferer(referer.getUri())
                        .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
                        .setCompletion(handler);
                  referer.sendRequest(getDirect);
               } else {
                  String message = String.format("DNS Lookup Error: No nodes found for [query=%s]", dnsLookupUri);
                  logger.log(Level.SEVERE, message);
                  throw new IllegalStateException(message);
               }
            }));

      referer.sendRequest(dnsLookupOperation);
   }

}
