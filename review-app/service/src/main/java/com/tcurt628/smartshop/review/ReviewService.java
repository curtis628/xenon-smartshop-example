package com.tcurt628.smartshop.review;

import com.tcurt628.smartshop.product.model.Product;
import com.tcurt628.smartshop.review.model.Review;
import com.tcurt628.smartshop.review.model.ReviewUpdateRequest;
import com.vmware.xenon.common.FactoryService;
import com.vmware.xenon.common.Operation;
import com.vmware.xenon.common.Service;
import com.vmware.xenon.common.ServiceDocumentQueryResult;
import com.vmware.xenon.common.StatefulService;
import com.vmware.xenon.common.UriUtils;
import com.vmware.xenon.common.Utils;
import com.vmware.xenon.dns.services.DNSService;
import com.vmware.xenon.services.common.ServiceUriPaths;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;

import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReviewService extends StatefulService {

   public static final Integer MIN_STAR = 1;
   public static final Integer MAX_STAR = 5;

   public static final String FACTORY_LINK = "/reviews";
   public static final String PRODUCT_SERVICE_NAME = "ProductService";
   public static final String PRODUCT_FACTORY_LINK = "/products";

   /** Not crazy about this, but it works for now I guess... */
   private static Logger logger = Logger.getLogger(ReviewService.class.getName());
   static {
      logger.setLevel(Level.FINE);
   }

   /** Create a default factory service that starts instances of this service on POST. */
   public static Service createFactory() {
      return FactoryService.create(ReviewService.class, Review.class);
   }

   public ReviewService() {
      super(Review.class);
      super.toggleOption(ServiceOption.PERSISTENCE, true);
      super.toggleOption(ServiceOption.REPLICATION, true);
      super.setPeerNodeSelectorPath(ReviewHost.REVIEW_NODE_SELECTOR_URI);
   }

   @Override
   public void handleStart(Operation post) {
      try {
         // validateInitialState handles marking 'post' as complete or fail
         validateInitialState(post);
      } catch (Exception e) {
         logWarning("Error on handleStart. [post=%s] [exception=%s]", post, e.getMessage());
         post.fail(e);
      }
   }

   @Override
   public void handlePut(Operation put) {
      throw new UnsupportedOperationException(
            "PUT not supported; Please use PATCH (with ReviewUpdateRequest) instead");
   }

   private void validateStars(Integer stars) {
      if (stars == null || stars < MIN_STAR || stars > MAX_STAR) {
         throw new IllegalArgumentException(
               String.format("stars: must be provided and be between %d and %d", MIN_STAR, MAX_STAR));
      }
   }

   private void validateContent(String content) {
      if (StringUtils.isEmpty(content)) {
         throw new IllegalArgumentException("content: cannot be empty");
      }
   }

   /** Validates initial state of a {@code Review}. Marks {@code post} as complete if successful. */
   private void validateInitialState(Operation post) throws InterruptedException {
      if (!post.hasBody()) {
         throw new IllegalArgumentException("Must include non-empty body");
      }

      Review state = post.getBody(Review.class);
      logInfo("Validating review: %s", state);

      validateStars(state.stars);
      validateContent(state.content);

      // Ensure that the product exists
      if (StringUtils.isEmpty(state.productLink)) {
         throw new IllegalArgumentException("productLink cannot be empty");
      }

      // For example purposes, we will show two different ways for querying the product service to
      // ensure the 'productLink' exists: 1. using node selector and 2. using DNS
      // We will use a CountDownLatch to ensure both ways validate properly before "completing"
      CountDownLatch latch = new CountDownLatch(2);
      Throwable[] failure = new Throwable[1];

      // 1st Way - is to query the node selector via a forwarding + odata query (odata is used so
      //           the GET doesn't block.
      String target = PRODUCT_FACTORY_LINK + String.format("?expand&$filter=(documentSelfLink eq '%s')", state.productLink);
      Operation getViaNodeSelector = Operation.createGet(this, target)
            .setReferer(this.getUri())
            .setCompletion(
                  (op, ex) -> {
                     try {
                        if (ex != null) {
                           logSevere("Error during node selector logic: [productLink=%s] [exception=%s]", state.productLink, ex);
                           failure[0] = ex;
                           return;
                        }

                        logFine("Successfully processed operation: %s", op);
                        ServiceDocumentQueryResult result = op.getBody(ServiceDocumentQueryResult.class);
                        Long count = result != null ? result.documentCount : 0;

                        Map<String, Object> matchedDocuments =
                              result == null || result.documents == null ? Collections.emptyMap() : result.documents;
                        Object productMatch = matchedDocuments.get(state.productLink);
                        logFine("Forwarding OData query returned %d results. Matching product details: %s",
                              count, productMatch);
                        if (productMatch == null) {
                           String message = String.format("[productLink=%s] does not exist via node-selector", state.productLink);
                           logWarning(message);
                           failure[0] = new IllegalArgumentException(message);
                           return;
                        }

                        Product product = Utils.fromJson(productMatch, Product.class);
                        logInfo("Product found using node selector! %s", product);
                     } finally { // Remember to countdown latch, regardless of error/success
                        latch.countDown();
                     }
                  }
            );
      logInfo("Forwarding odata GET query for: %s", getViaNodeSelector.getUri());
      this.getHost().forwardRequest(ReviewHost.PRODUCT_NODE_SELECTOR_URI, getViaNodeSelector);

      // 2nd Way - is to query the DNS, get the host where product is running and issue a GET on that directly
      String productServiceDNSLookupQuery = String.format("$filter=serviceName eq %s", PRODUCT_SERVICE_NAME);
      URI productServiceDNSLookupURI = UriUtils.buildUri(
            ReviewHost.hostArguments.dnshost,
            ReviewHost.hostArguments.dnsport,
            ServiceUriPaths.DNS + "/query",
            productServiceDNSLookupQuery);

      Operation.CompletionHandler productLookupHandler = (op, ex) -> {
         try {
            if (ex != null) {
               String message = String.format("[productLink=%s] does not exist when using GET provided by DNS lookup", state.productLink);
               logInfo(message);
               failure[0] = new IllegalArgumentException(message);
               return;
            }

            Product product = op.getBody(Product.class);
            logInfo("Product found using DNS! %s", product);
         } finally { // Remember to countdown latch, regardless of error/success
            latch.countDown();
         }
      };

      Operation.CompletionHandler productServiceDNSLookupQueryHandler = (o, e) -> {
         if(e != null) {
            logSevere("Error looking up DNS for product service: [Exception=%s]", e);
            throw new IllegalStateException("Error looking up DNS for product service", e);
         }

         ServiceDocumentQueryResult result = o.getBody(ServiceDocumentQueryResult.class);
         if(result.documentLinks == null || result.documentLinks.size() != 1) {
            logSevere("Error during product service DNS lookup: DocumentLinks is wrong [productLink=%s] [documentLinks=%s]",
                  state.productLink, result.documentLinks);
            throw new IllegalStateException("Error looking up DNS for product service: " + state.productLink, e);
         }

         String documentKey = result.documentLinks.get(0);
         logInfo("DNS service lookup returned %d record with [documentKey=%s]", result.documentCount, documentKey);
         Object documentValue = result.documents.get(documentKey);
         DNSService.DNSServiceState serviceState = Utils.fromJson(documentValue, DNSService.DNSServiceState.class);
         logFine("Retrieved [dnsServiceState=%s]", ToStringBuilder.reflectionToString(serviceState));

         if(serviceState.nodeReferences.size() > 0) {
            URI productURI = UriUtils.extendUri(serviceState.nodeReferences.iterator().next(), state.productLink);
            logInfo("DNS Response for product: %s", productURI);

            // Add "no queuing" directive because, by default, it will wait on the service for it to be created
            // NO_QUEUING says return immediately, even if the service doesn't exist yet (a HTTP 404)
            Operation getProductDirect = Operation.createGet(productURI)
                  .setReferer(this.getUri())
                  .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_QUEUING)
                  .setCompletion(productLookupHandler);
            this.getHost().sendRequest(getProductDirect);
         } else {
            logSevere("Error during product service DNS lookup: No Nodes found [ProductLink=%s], [Exception=%s]", state.productLink, e);
            throw new IllegalStateException("Error looking up DNS for product service: " + state.productLink, e);
         }
      };

      Operation getProductNodeFromDNS = Operation.createGet(productServiceDNSLookupURI)
            .setCompletion(productServiceDNSLookupQueryHandler)
            .setReferer(this.getUri());

      logInfo("Sending DNS request: %s", getProductNodeFromDNS.getUri());
      this.getHost().sendRequest(getProductNodeFromDNS);

      // Wait on latch to ensure both methods of communicating with ProductService was successful
      if (!latch.await(getHost().getState().operationTimeoutMicros, TimeUnit.MICROSECONDS)) {
         failure[0] = new TimeoutException();
      }

      if (failure[0] != null) {
         post.fail(failure[0]);
         return;
      }

      post.complete();
      logInfo("Review created successfully! [review=%s]", post.getBody(Review.class));
   }

   @Override
   public void handlePatch(Operation patch) {
      logInfo("handlePatch(). [patch=%s]", patch);
      Review currentState = getState(patch);
      ReviewUpdateRequest updateRequest = patch.getBody(ReviewUpdateRequest.class);

      try {
         validateStars(updateRequest.stars);
         validateContent(updateRequest.content);

         currentState.stars = updateRequest.stars;
         currentState.content = updateRequest.content;
      } catch (Exception e) {
         logWarning("Error during PATCH. [patch=%s] [error=%s]", patch, e);
         patch.fail(e);
         return;
      }

      setState(patch, currentState);
      patch.setBody(currentState);
      patch.complete();
   }
}
